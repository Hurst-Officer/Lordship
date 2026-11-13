package io.github.lordship.presence.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import io.github.lordship.access.Agent;
import io.github.lordship.access.AgentService;
import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.persons.Person;
import io.github.lordship.persons.PersonService;
import io.github.lordship.shared.PropertyScope;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Who is where in Glade right now, and what changed. Kept in a Caffeine cache only: nothing here is saved or audited.
 * Changes reach the windows two ways: a window announces its own save ("saved", from Glade), and the database
 * announces every committed change to a table Glade shows, whoever made it ("changed", from ChangeNotices).
 * One entry per Glade window, so an agent with two windows open does not flicker.
 * Every change is pushed to every open stream, filtered by the viewer's property scope.
 *
 * <p>Every write to a stream happens on one thread (the sender). So two writes never meet on one
 * stream, no lock is held while writing, and a slow computer never holds up a request.
 *
 * <p>This works because the office app runs on one server. A second office server
 * would need a shared relay (Postgres LISTEN/NOTIFY would do).
 */
@Component
public class PresenceHub {

    // A window that has not checked in for this long is gone (a crash, or the computer went to sleep).
    // Glade checks in every 15 seconds. A minimized window may only manage once a minute, so this is longer.
    static final Duration STALE = Duration.ofSeconds(75);

    // How often open streams are pinged. A ping that fails means the connection is dead.
    static final long PING_SECONDS = 15;

    // A stream is closed after this long. Glade opens a new one with a fresh access token.
    static final long STREAM_MS = Duration.ofMinutes(10).toMillis();

    static final int MAX_TEXT = 200;

    // A window reports at most this many picks. A whole park is a few hundred lots.
    static final int MAX_PICKS = 2000;

    private final PersonService personService;
    private final AgentService agentService;

    // Each check-in puts the window again, which restarts its time.
    // When a window runs out, everyone else is told it left.
    private final Cache<UUID, Window> cache = Caffeine.newBuilder()
            .expireAfterWrite(STALE)
            // without a scheduler, Caffeine only notices an expired window on the next read
            .scheduler(Scheduler.systemScheduler())
            .removalListener((UUID client, Window window, RemovalCause cause) -> {
                if (cause == RemovalCause.EXPIRED && window != null) {
                    closeStream(window);
                    queueRoster();
                }
            })
            .build();

    // The cache as a map, for walking every window and for removing one only if it is still the same window
    private final Map<UUID, Window> windows = cache.asMap();

    // The one thread that writes to streams: rosters, saved events and pings
    private final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "presence-send");
        thread.setDaemon(true);
        return thread;
    });

    // True while a roster broadcast is waiting to run. Ten quick changes then send one roster, not ten.
    private final AtomicBoolean rosterQueued = new AtomicBoolean(false);

    public PresenceHub(PersonService personService, AgentService agentService) {
        this.personService = personService;
        this.agentService = agentService;
        sender.scheduleWithFixedDelay(this::ping, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        sender.shutdownNow();
    }

    /**
     * Records where a window is, which box it has selected, and what it has picked there.
     * False when the window id belongs to another agent.
     */
    public boolean update(AgentPrincipal who, UUID client, String place, String field, boolean typing,
                          List<String> picks) {
        Window window = windowFor(who, client);
        if (window == null) {
            return false;
        }

        String cleanPlace = clean(place);
        // a box only counts inside a place, and typing only inside a box
        String cleanField = cleanPlace == null ? null : clean(field);
        boolean cleanTyping = cleanField != null && typing;
        // picks only count inside a place
        List<String> cleanPicks = cleanPlace == null ? List.of() : cleanList(picks);

        boolean changed;
        synchronized (window) {
            window.scope = who.propertyScope();
            changed = !Objects.equals(window.place, cleanPlace)
                    || !Objects.equals(window.field, cleanField)
                    || window.typing != cleanTyping
                    || !window.picks.equals(cleanPicks);
            window.place = cleanPlace;
            window.field = cleanField;
            window.typing = cleanTyping;
            window.picks = cleanPicks;
        }
        // restarts the window's time
        cache.put(client, window);

        if (changed) {
            queueRoster();
        }
        return true;
    }

    /**
     * Opens the stream the window hears everyone else on. It gets the current roster right after.
     * Null when the window id belongs to another agent.
     */
    public SseEmitter open(AgentPrincipal who, UUID client) {
        Window window = windowFor(who, client);
        if (window == null) {
            return null;
        }

        SseEmitter stream = new SseEmitter(STREAM_MS);
        SseEmitter old;
        synchronized (window) {
            window.scope = who.propertyScope();
            old = window.stream;
            window.stream = stream;
        }
        cache.put(client, window);
        if (old != null) {
            closeQuietly(old);
        }

        // The ten minutes are up. The stream is let go first, so nothing is written to it while it closes.
        // Glade reconnects, so the window stays on the roster.
        stream.onTimeout(() -> {
            detach(client, stream);
            stream.complete();
        });
        stream.onCompletion(() -> detach(client, stream));
        // The connection broke, so the window is gone
        stream.onError(error -> drop(client, stream));

        later(() -> write(client, window, rosterEvent(window)));
        return stream;
    }

    /**
     * A window saved something. Every other window that may see the place is told, so it can load the new value.
     * The sender's other windows are told too, with byYou set. Nothing is sent back to the sending window.
     * False when the window id belongs to another agent.
     */
    public boolean saved(AgentPrincipal who, UUID client, String place, String field) {
        Window sender = windowFor(who, client);
        if (sender == null) {
            return false;
        }
        String cleanPlace = clean(place);
        if (cleanPlace == null) {
            return true;
        }
        String cleanField = clean(field);
        String name;
        String color;
        synchronized (sender) {
            name = sender.name;
            color = sender.color;
        }

        later(() -> {
            for (Map.Entry<UUID, Window> entry : windows.entrySet()) {
                Window viewer = entry.getValue();
                if (entry.getKey().equals(client) || !visibleTo(viewer.agentId, scopeOf(viewer), cleanPlace)) {
                    continue;
                }
                boolean byYou = viewer.agentId.equals(sender.agentId);
                SavedEvent saved = new SavedEvent(sender.agentId, name, color, cleanPlace, cleanField, byYou);
                write(entry.getKey(), viewer, SseEmitter.event().name("saved").data(saved, MediaType.APPLICATION_JSON));
            }
        });
        return true;
    }

    /**
     * Rows changed in the database (ChangeNotices), by the Glade place that shows them and the table that changed.
     * Every window that may see a place is told in one "changed" event, the window that made the change included
     * (the database does not know which window did). "*" reaches every window: load everything again.
     */
    public void changed(Map<String, String> tableByPlace) {
        if (tableByPlace.isEmpty()) {
            return;
        }
        later(() -> {
            for (Map.Entry<UUID, Window> entry : windows.entrySet()) {
                Window viewer = entry.getValue();
                PropertyScope scope = scopeOf(viewer);
                List<ChangedEvent.Place> shown = new ArrayList<>();
                tableByPlace.forEach((place, table) -> {
                    if (place.equals(ChangePlaces.EVERYTHING) || visibleTo(viewer.agentId, scope, place)) {
                        shown.add(new ChangedEvent.Place(place, table));
                    }
                });
                if (!shown.isEmpty()) {
                    write(entry.getKey(), viewer,
                            SseEmitter.event().name("changed").data(new ChangedEvent(shown), MediaType.APPLICATION_JSON));
                }
            }
        });
    }

    // How an agent is shown to coworkers: their name, and the color they picked (null when they picked none)
    record Shown(String name, String color) { }

    /**
     * Agents whose name or color may have changed in the database (ChangeRelay, from a change notice on agent or
     * person). Their open windows take the name and color given, and everyone else gets one roster when anything
     * is different. An agent with no open window is skipped.
     */
    public void agentsChanged(Map<UUID, Shown> shownByAgent) {
        if (shownByAgent.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Window window : windows.values()) {
            Shown shown = shownByAgent.get(window.agentId);
            if (shown == null) {
                continue;
            }
            synchronized (window) {
                if (!Objects.equals(window.name, shown.name()) || !Objects.equals(window.color, shown.color())) {
                    window.name = shown.name();
                    window.color = shown.color();
                    changed = true;
                }
            }
        }
        if (changed) {
            queueRoster();
        }
    }

    /** The window is closing. Everyone else sees it leave right away. */
    public void leave(AgentPrincipal who, UUID client) {
        Window window = windows.get(client);
        if (window == null || !window.agentId.equals(who.agentUuid())) {
            return;
        }
        if (windows.remove(client, window)) {
            closeStream(window);
            queueRoster();
        }
    }

    // Pings every open stream (on the sender thread). A ping that fails drops that window.
    // Windows that stop checking in are dropped by the cache.
    void ping() {
        try {
            for (Map.Entry<UUID, Window> entry : windows.entrySet()) {
                write(entry.getKey(), entry.getValue(), SseEmitter.event().comment("ping"));
            }
        } catch (RuntimeException e) {
            // A failed ping must not stop the next one
        }
    }

    // Sends every open stream the agents its viewer may see. Runs on the sender thread.
    void broadcastRoster() {
        for (Map.Entry<UUID, Window> entry : windows.entrySet()) {
            write(entry.getKey(), entry.getValue(), rosterEvent(entry.getValue()));
        }
    }

    // Asks for one roster broadcast, unless one is already waiting
    private void queueRoster() {
        if (rosterQueued.compareAndSet(false, true)) {
            later(() -> {
                rosterQueued.set(false);
                broadcastRoster();
            });
        }
    }

    // Runs a task on the sender thread. A failing task must not stop the ones after it.
    private void later(Runnable task) {
        try {
            sender.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    // the next change sends again
                }
            });
        } catch (RejectedExecutionException e) {
            // the server is shutting down
        }
    }

    // Writes one event to a window's stream. Only the sender thread calls this.
    // IOException: the connection is dead, so the window is dropped.
    // IllegalStateException: the stream already finished, so it is only let go. The window reconnects.
    private void write(UUID client, Window window, SseEmitter.SseEventBuilder event) {
        SseEmitter stream;
        synchronized (window) {
            stream = window.stream;
        }
        if (stream == null) {
            return;
        }
        try {
            stream.send(event);
        } catch (IOException e) {
            drop(client, stream);
        } catch (IllegalStateException e) {
            detach(client, stream);
        }
    }

    private SseEmitter.SseEventBuilder rosterEvent(Window viewer) {
        return SseEmitter.event().name("roster").data(new RosterResponse(rosterFor(viewer)), MediaType.APPLICATION_JSON);
    }

    // The roster one viewer may see: everyone else who is somewhere,
    // except places under a property the viewer is not assigned to
    List<PresentAgent> rosterFor(Window viewer) {
        PropertyScope scope = scopeOf(viewer);
        List<PresentAgent> agents = new ArrayList<>();
        for (Window other : windows.values()) {
            if (other.agentId.equals(viewer.agentId)) {
                continue;
            }
            synchronized (other) {
                if (other.place != null && visibleTo(viewer.agentId, scope, other.place)) {
                    agents.add(new PresentAgent(other.agentId, other.name, other.color, other.place, other.field,
                            other.typing, other.picks));
                }
            }
        }
        return agents;
    }

    private static PropertyScope scopeOf(Window window) {
        synchronized (window) {
            return window.scope;
        }
    }

    // The window for this id, made on first use. Null when the id belongs to another agent.
    private Window windowFor(AgentPrincipal who, UUID client) {
        Window window = windows.get(client);
        if (window == null) {
            // the name and the color are looked up outside the map, so the map is never held during a database call
            Window made = new Window(who.agentUuid(), nameOf(who), colorOf(who), who.propertyScope());
            Window raced = windows.putIfAbsent(client, made);
            window = raced == null ? made : raced;
        }
        return window.agentId.equals(who.agentUuid()) ? window : null;
    }

    private String nameOf(AgentPrincipal who) {
        return personService.findByID(who.personUuid())
                .map(Person::nameFull)
                .filter(name -> !name.isBlank())
                .orElse(NO_NAME);
    }

    // The color the agent picked to be shown in, or null when they picked none
    private String colorOf(AgentPrincipal who) {
        return agentService.findById(who.agentUuid()).map(Agent::color).orElse(null);
    }

    // What an agent without a name is called on the roster
    static final String NO_NAME = "Someone";

    // The stream ended normally. The window stays until it stops checking in.
    private void detach(UUID client, SseEmitter stream) {
        Window window = windows.get(client);
        if (window == null) {
            return;
        }
        synchronized (window) {
            if (window.stream == stream) {
                window.stream = null;
            }
        }
    }

    // The connection broke. The window is dropped and everyone else is told.
    private void drop(UUID client, SseEmitter stream) {
        Window window = windows.get(client);
        if (window == null) {
            return;
        }
        boolean current;
        synchronized (window) {
            current = window.stream == stream;
            if (current) {
                window.stream = null;
            }
        }
        if (current && windows.remove(client, window)) {
            queueRoster();
        }
    }

    private static void closeStream(Window window) {
        SseEmitter stream;
        synchronized (window) {
            stream = window.stream;
            window.stream = null;
        }
        if (stream != null) {
            closeQuietly(stream);
        }
    }

    private static void closeQuietly(SseEmitter stream) {
        try {
            stream.complete();
        } catch (RuntimeException e) {
            // already closed
        }
    }

    // An agent's own page in Glade: "self/<agentId>". Only that agent's windows see it.
    static final String SELF = "self";

    // A place under a property the viewer is not assigned to stays hidden from them,
    // and an agent's own page is hidden from everyone else
    static boolean visibleTo(UUID viewerAgentId, PropertyScope scope, String place) {
        String[] parts = place.split("/");
        if (parts[0].equals(SELF)) {
            return parts.length >= 2 && parts[1].equals(viewerAgentId.toString());
        }
        UUID propertyId = propertyOf(place);
        return propertyId == null || scope.allows(propertyId);
    }

    // "properties/<propertyId>/lots/<lotId>" gives the property id.
    // The second part of a place is the property for every concern that has one. Null otherwise.
    // ("self/<agentId>" is not a property; visibleTo deals with it first.)
    static UUID propertyOf(String place) {
        String[] parts = place.split("/");
        if (parts.length < 2) {
            return null;
        }
        try {
            return UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Blank is null. Long text is cut short, so a bad client cannot fill the roster.
    static String clean(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.length() > MAX_TEXT ? trimmed.substring(0, MAX_TEXT) : trimmed;
    }

    // Each pick cleaned like any text, blanks and repeats dropped, and the list cut at MAX_PICKS
    static List<String> cleanList(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return texts.stream()
                .map(PresenceHub::clean)
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_PICKS)
                .toList();
    }

    // One Glade window. Everything after agentId can change, so it is read and written while holding the window.
    // The lock is only held for these fields, never while writing to the stream.
    // name and color are the agent's as they were when the window opened; agentsChanged keeps them current.
    static final class Window {
        final UUID agentId;
        String name;
        String color;
        PropertyScope scope;
        String place;
        String field;
        boolean typing;
        List<String> picks = List.of();
        SseEmitter stream;

        Window(UUID agentId, String name, String color, PropertyScope scope) {
            this.agentId = agentId;
            this.name = name;
            this.color = color;
            this.scope = scope;
        }
    }
}

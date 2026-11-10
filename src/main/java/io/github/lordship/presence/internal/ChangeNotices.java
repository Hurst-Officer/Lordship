package io.github.lordship.presence.internal;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Hears every change to a table Glade shows (V16: pg_notify on lordship_change, sent when a transaction commits),
 * whoever made it, and passes it on to the open Glade windows (ChangeRelay). So a screen follows changes made by a
 * coworker, by the server itself (a sign-in, a billing run), or outside Glade (psql, an import), not only saves
 * another Glade window announced.
 *
 * <p>One thread holds one connection from the pool for as long as Lordship runs, LISTENing. A burst of notices
 * (a billing run over a whole park) is gathered for a moment and passed on as one batch. When the connection
 * breaks, it opens a new one; notices sent meanwhile are lost, so every window is told to load everything again.
 *
 * <p>lordship.change-notices.enabled=false turns it off (Glade then only hears saves other windows announce).
 */
@Component
class ChangeNotices {

    private static final Logger log = LoggerFactory.getLogger(ChangeNotices.class);

    static final String CHANNEL = "lordship_change";

    // How long one wait for a notice lasts before the thread checks whether Lordship is stopping
    private static final int POLL_MS = 1000;
    // A burst is gathered while notices keep coming this close together, for at most BURST_MS, up to MAX_BATCH
    private static final int BURST_POLL_MS = 50;
    private static final long BURST_MS = 300;
    private static final int MAX_BATCH = 5000;

    private final DataSource dataSource;
    private final ChangeRelay relay;
    private final boolean enabled;

    private volatile boolean running;
    private volatile boolean listening;
    private Thread thread;

    ChangeNotices(DataSource dataSource, ChangeRelay relay,
                  @Value("${lordship.change-notices.enabled:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.relay = relay;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    synchronized void start() {
        if (!enabled || running) return;
        running = true;
        thread = new Thread(this::listen, "change-notices");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    synchronized void stop() throws InterruptedException {
        running = false;
        if (thread != null) {
            thread.join(POLL_MS * 2L);
        }
    }

    // True while LISTEN is in place (for tests, which must not send before it is)
    boolean isListening() {
        return listening;
    }

    private void listen() {
        long wait = 1000;
        boolean first = true;
        while (running) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                PgNotifications notifications = new PgNotifications(connection);
                listening = true;
                if (!first) {
                    relay.everything();
                }
                first = false;
                wait = 1000;

                while (running) {
                    List<String> arrived = notifications.poll(POLL_MS);
                    if (arrived.isEmpty()) continue;
                    List<String> batch = new ArrayList<>(arrived);
                    long until = System.currentTimeMillis() + BURST_MS;
                    while (batch.size() < MAX_BATCH && System.currentTimeMillis() < until) {
                        List<String> more = notifications.poll(BURST_POLL_MS);
                        if (more.isEmpty()) break;
                        batch.addAll(more);
                    }
                    pass(batch);
                }
            } catch (Exception e) {
                listening = false;
                if (!running) return;
                log.warn("Change notices stopped ({}); listening again in {} ms", e.toString(), wait);
                pause(wait);
                wait = Math.min(wait * 2, 30_000);
            } finally {
                listening = false;
            }
        }
    }

    // A batch that cannot be placed (a lookup failed) still reaches the windows, as "load everything again"
    private void pass(List<String> batch) {
        try {
            relay.relay(batch);
        } catch (RuntimeException e) {
            log.warn("A batch of {} change notices could not be placed ({}); every window loads again", batch.size(), e.toString());
            relay.everything();
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

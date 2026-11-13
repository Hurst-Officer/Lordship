package io.github.lordship.presence.internal;

import io.github.lordship.identity.AgentPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Who else is looking at the same screen, and which box they have selected.
 * Any signed-in agent may use it; anyone else gets 403. Nothing is saved and nothing is audited.
 * Places under a property the agent is not assigned to are left out of what they hear.
 */
@RestController
@RequestMapping("/api/presence")
public class PresenceController {

    private final PresenceHub hub;

    public PresenceController(PresenceHub hub) {
        this.hub = hub;
    }

    /**
     * The stream a window hears everyone else on (Server-Sent Events).
     * Each "roster" event carries { agents: [{ agentId, name, color, place, field, typing, picks }] }, the viewer
     * left out. color is the color that agent picked to be shown in (AgentColors), or null when they picked none.
     * A new roster goes out when an agent's name or color changes, too.
     * A "saved" event { agentId, name, color, place, field, byYou } means someone saved something there (see /saved).
     * A "changed" event { places: [{ place, table }] } means rows shown on those places changed in the database,
     * whoever changed them (ChangeNotices); place "*" means load everything again.
     * The first one arrives at once. A ":ping" comment comes every 15 seconds.
     * The stream closes after 10 minutes; open a new one with a fresh token.
     * 403 when the client id belongs to another agent.
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestParam UUID client, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof AgentPrincipal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        SseEmitter stream = hub.open(agent(authentication), client);
        if (stream == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(stream);
    }

    /**
     * Where the window is now. Send it when the place, the selected box, typing or the picks change,
     * and every 15 seconds besides. A window silent for 75 seconds is dropped.
     * 204, or 403 when the client id belongs to another agent.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<Void> update(@Valid @RequestBody PresenceUpdateRequest request, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof AgentPrincipal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        boolean saved = hub.update(agent(authentication), request.client(),
                request.place(), request.field(), request.typing(), request.picks());
        return saved
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /**
     * A save went through in this window. The other windows that may see the place get a "saved" event
     * { agentId, name, color, place, field, byYou } and load the record again.
     * 204, or 403 when the client id belongs to another agent.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/saved")
    public ResponseEntity<Void> saved(@Valid @RequestBody PresenceSavedRequest request, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof AgentPrincipal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        boolean sent = hub.saved(agent(authentication), request.client(), request.place(), request.field());
        return sent
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /** The window is closing. Always 204. */
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/{client}")
    public ResponseEntity<Void> leave(@PathVariable UUID client, Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof AgentPrincipal)) {
            return ResponseEntity.noContent().build();
        }
        hub.leave(agent(authentication), client);
        return ResponseEntity.noContent().build();
    }

    private static AgentPrincipal agent(Authentication authentication) {
        return (AgentPrincipal) authentication.getPrincipal();
    }
}

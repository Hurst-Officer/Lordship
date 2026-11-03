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
 * Any signed-in agent may use it. Nothing is saved and nothing is audited.
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
     * Each "roster" event carries { agents: [{ agentId, name, place, field, typing }] }, the viewer left out.
     * A "saved" event { agentId, name, place, field, byYou } means someone saved something there (see /saved).
     * The first one arrives at once. A ":ping" comment comes every 15 seconds.
     * The stream closes after 10 minutes; open a new one with a fresh token.
     * 403 when the client id belongs to another agent.
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestParam UUID client, Authentication authentication) {
        SseEmitter stream = hub.open(agent(authentication), client);
        if (stream == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(stream);
    }

    /**
     * Where the window is now. Send it when the place, the selected box or typing changes,
     * and every 15 seconds besides. A window silent for 75 seconds is dropped.
     * 204, or 403 when the client id belongs to another agent.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<Void> update(@Valid @RequestBody PresenceUpdateRequest request, Authentication authentication) {
        boolean saved = hub.update(agent(authentication), request.client(),
                request.place(), request.field(), request.typing());
        return saved
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /**
     * A save went through in this window. The other windows that may see the place get a "saved" event
     * { agentId, name, place, field, byYou } and load the record again.
     * 204, or 403 when the client id belongs to another agent.
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/saved")
    public ResponseEntity<Void> saved(@Valid @RequestBody PresenceSavedRequest request, Authentication authentication) {
        boolean sent = hub.saved(agent(authentication), request.client(), request.place(), request.field());
        return sent
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /** The window is closing. Always 204. */
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/{client}")
    public ResponseEntity<Void> leave(@PathVariable UUID client, Authentication authentication) {
        hub.leave(agent(authentication), client);
        return ResponseEntity.noContent().build();
    }

    private static AgentPrincipal agent(Authentication authentication) {
        return (AgentPrincipal) authentication.getPrincipal();
    }
}

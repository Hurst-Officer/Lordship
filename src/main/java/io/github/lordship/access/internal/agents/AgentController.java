package io.github.lordship.access.internal.agents;

import io.github.lordship.access.*;
import io.github.lordship.identity.AgentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * 201 with the new agent. A password that passes the 12-64 character check but is
     * over 72 bytes (accents and symbols count double or more) comes back 400 with
     * {@code code: password.too_long} and {@code field: password} -- show it on the box.
     */
    @PreAuthorize("hasAuthority('agents:create')")
    @PostMapping
    public ResponseEntity<AgentRegistrationResponse> registerAgent(@Valid @RequestBody AgentRegistrationRequest request){

        AgentWithPerson agentWithPerson = agentService.registerAgent(request.nameFull(), request.workPhone(), request.workEmail(), request.password());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(AgentRegistrationResponse.from(agentWithPerson));
    }

    /**
     * Sign-in. What the frontend can get back:
     * <ul>
     *   <li>200 -- the token, plus the agent's {@code roles} and {@code permissions}
     *       (sorted names). The token is good until {@code jwt.expiration-ms}; nothing
     *       here touches an agent who is already signed in. Also a {@code refreshToken}
     *       for {@code /session/refresh}. Keep it somewhere safe, never in plain storage.</li>
     *   <li>401, {@code code: auth.bad_credentials} -- the same body for an unknown email
     *       and a wrong password, on purpose. Show {@code message}; don't try to say which.</li>
     *   <li>429, {@code code: auth.too_many_attempts} plus a {@code Retry-After} header in
     *       seconds -- LoginThrottle refused it before the password was checked, so even
     *       the right password gets this. Show {@code message}, disable the button until
     *       Retry-After runs out, and do NOT retry on a loop: every retry is another attempt.
     *       Usually 1 second (the server-wide limit), up to 15 minutes (too many from this
     *       computer, or at this email from this computer).</li>
     *   <li>413 -- body over 4 KB, from LoginBodySizeFilter. A real form never sends one.</li>
     *   <li>400 -- the email or password failed validation ({@code message} only, no code).</li>
     * </ul>
     * {@code message} follows Accept-Language; {@code code} never changes, so branch on it.
     * Limits live in application.properties under {@code lordship.login.*}.
     */
    @PostMapping("/auth")
    public ResponseEntity<AgentLoginResponse> login(@Valid @RequestBody AgentLoginRequest request, HttpServletRequest httpRequest) {

        return agentService.verifyLogin(request.workEmail(),
                                        request.password(),
                                        httpRequest.getHeader("User-Agent"),
                                        httpRequest.getRemoteAddr()
                )
                .map(AgentLoginResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(LoginRefused::badCredentials);
    }

    /**
     * Keeps a computer signed in. Trades a refresh token for a new access token and a
     * new refresh token, with the same body as sign-in. The old refresh token stops
     * working right away, so save the new one before anything else.
     * <ul>
     *   <li>200 -- same body as {@code /auth}.</li>
     *   <li>401, {@code code: auth.session_expired} -- unknown, already used, logged out,
     *       idle past {@code lordship.session.idle-days}, older than {@code lordship.session.max-days},
     *       or ended by a password reset. Show the sign-in screen.</li>
     * </ul>
     * Sending an old refresh token a second time ends that whole session. Never retry
     * a refresh with the token you just sent.
     */
    @PostMapping("/session/refresh")
    public ResponseEntity<AgentLoginResponse> refreshSession(@Valid @RequestBody RefreshTokenRequest request,
                                                             HttpServletRequest httpRequest) {
        return agentService.refreshSession(request.refreshToken(), httpRequest.getRemoteAddr())
                .map(AgentLoginResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(LoginRefused::sessionExpired);
    }

    /**
     * Log out of this computer. Always 204, even for a token that is unknown or already ended.
     * The access token keeps working until it expires (at most {@code jwt.expiration-ms}).
     */
    @PostMapping("/session/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        agentService.endSession(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /**
     * Who am I? Same body as the login response, without the token.
     * Any valid token works; no permission is needed. No token gets a 401.
     * Glade calls this after a 403 to redraw the screen without a new login.
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/self")
    public ResponseEntity<AgentSelfResponse> getSelf(Authentication authentication) {
        AgentPrincipal principal = (AgentPrincipal) authentication.getPrincipal();

        // the same authorities the 403 check just used for this request
        List<String> permissions = authentication.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        return agentService.findSelf(principal.agentUuid(), permissions)
                .map(AgentSelfResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }


    /**
     * 204 when set, and every token the agent held stops working (they sign in again).
     * Over 72 bytes: 400, {@code code: password.too_long}, {@code field: newPassword}.
     * Does not reset LoginThrottle -- an agent locked out at sign-in still waits out the window.
     */
    @PreAuthorize("hasAuthority('agents:reset_passwords')")
    @PutMapping("/{uuid}/password")
    public ResponseEntity<Void> changePassword(@PathVariable UUID uuid,
                                                  @Valid @RequestBody ChangePasswordRequest request){
        return agentService.setAgentPassword(uuid, request.newPassword())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /**
     * The agent's signed-in computers, most recently used first. Ended, idle and expired
     * sessions are left out. {@code thisComputer} marks the one the caller is using.
     * 404 for an unknown or removed agent.
     */
    @PreAuthorize("hasAuthority('agents:end_sessions')")
    @GetMapping("/{uuid}/sessions")
    public ResponseEntity<List<AgentSessionResponse>> listSessions(@PathVariable UUID uuid,
                                                                   HttpServletRequest httpRequest) {
        UUID callerSessionId = (UUID) httpRequest.getAttribute(JwtService.SESSION_ID_ATTRIBUTE);
        return agentService.listSessions(uuid)
                .map(rows -> rows.stream()
                        .map(row -> AgentSessionResponse.from(row, callerSessionId))
                        .toList())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Sign one computer out. 204, or 404 when the session is not this agent's or already ended.
     * That computer shows the sign-in screen at its next refresh, within {@code jwt.expiration-ms}.
     */
    @PreAuthorize("hasAuthority('agents:end_sessions')")
    @DeleteMapping("/{uuid}/sessions/{sessionId}")
    public ResponseEntity<Void> signOutComputer(@PathVariable UUID uuid, @PathVariable UUID sessionId) {
        return agentService.signOutComputer(uuid, sessionId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** Sign out every computer the agent uses. 204, or 404 for an unknown or removed agent. */
    @PreAuthorize("hasAuthority('agents:end_sessions')")
    @DeleteMapping("/{uuid}/sessions")
    public ResponseEntity<Void> signOutEverywhere(@PathVariable UUID uuid) {
        return agentService.signOutEverywhere(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /**
     * Change the agent's job title, as shown in Personnel. A title is not a role and grants nothing.
     * 204, or 404 for an unknown or removed agent.
     * 400 with {@code code: agent.title_blank} and {@code field: title} for a blank title.
     */
    @PreAuthorize("hasAuthority('agents:edit')")
    @PutMapping("/{uuid}/title")
    public ResponseEntity<Void> setTitle(@PathVariable UUID uuid, @Valid @RequestBody AgentTitleRequest request) {
        return agentService.setTitle(uuid, request.title())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /**
     * Remove an agent (soft delete). 204, or 404 for an unknown or already removed agent.
     * Their tokens stop working right away (401).
     * 409 with {@code code: agent.cannot_remove_self} or {@code code: agent.last_admin}.
     */
    @PreAuthorize("hasAuthority('agents:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> removeAgent(@PathVariable UUID uuid, Authentication authentication) {
        AgentPrincipal principal = (AgentPrincipal) authentication.getPrincipal();
        return agentService.removeAgent(uuid, principal.agentUuid())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}

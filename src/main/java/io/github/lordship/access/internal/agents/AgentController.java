package io.github.lordship.access.internal.agents;

import io.github.lordship.access.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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
     *   <li>200 -- the token. It is good until {@code jwt.expiration-ms}; nothing here
     *       touches an agent who is already signed in.</li>
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
                .map(result -> AgentLoginResponse.from(result.agentWithPerson(), result.token()))
                .map(ResponseEntity::ok)
                .orElseThrow(LoginRefused::badCredentials);
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


}

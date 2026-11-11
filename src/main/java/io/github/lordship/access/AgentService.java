package io.github.lordship.access;


import eu.bitwalker.useragentutils.UserAgent;
import io.github.lordship.access.internal.*;
import io.github.lordship.access.internal.agents.AgentRepository;
import io.github.lordship.access.internal.agents.AgentRow;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.identity.AgentAuthorizationCache;
import io.github.lordship.persons.Person;
import io.github.lordship.persons.PersonService;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.shared.SystemPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class AgentService {

    private final AgentRepository agentRepository;
    private final PersonService personService;
    private final PasswordService passwordService;
    private final PermissionService permissionService;
    private final JwtService jwtService;
    private final GrantedRoleService grantedRoleService;
    private final LoginEventRepository loginEventRepository;
    private final AuditService auditService;
    private final AgentAuthorizationCache authorizationCache;
    private final LoginThrottle loginThrottle;
    private final AgentSessionService sessionService;
    private final AccessKeys accessKeys;

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private static final String ADMIN_ROLE = "Admin";


    public AgentService(
            AgentRepository agentRepository,
            PersonService personService,
            PasswordService passwordService,
            PermissionService permissionService,
            JwtService jwtService,
            GrantedRoleService grantedRoleService,
            LoginEventRepository loginEventRepository,
            AuditService auditService,
            AgentAuthorizationCache authorizationCache,
            LoginThrottle loginThrottle,
            AgentSessionService sessionService,
            AccessKeys accessKeys
    ) {
        this.agentRepository = agentRepository;
        this.personService = personService;
        this.passwordService = passwordService;
        this.permissionService = permissionService;
        this.jwtService = jwtService;
        this.grantedRoleService = grantedRoleService;
        this.loginEventRepository = loginEventRepository;
        this.auditService = auditService;
        this.authorizationCache = authorizationCache;
        this.loginThrottle = loginThrottle;
        this.sessionService = sessionService;
        this.accessKeys = accessKeys;
    }

    @Transactional
    public AgentWithPerson registerAgent(String nameFull, String workPhone, String workEmail, String plainTextPassword) {
        passwordService.requireFits(plainTextPassword, "password");

        Person person = personService.createPersonFromName(nameFull);

        // hash the pass before it enters the DB
        String hashed = passwordService.hash(plainTextPassword);

        AgentRow agentRow = new AgentRow(
                person.uuid(),
                workPhone,
                workEmail,
                hashed
        );

        Agent agent = agentRepository.save(agentRow).toAgent();

        // log the change
        auditService.recordInsert("agent", agent.uuid(), AuditMapper.toMap(agent));

        return new AgentWithPerson(agent, person);
    }

    @Transactional
    public boolean setAgentPassword(UUID agentId, String plainTextPassword) {
        passwordService.requireFits(plainTextPassword, "newPassword");

        Optional<AgentRow> found = agentRepository.findById(agentId);
        if (found.isEmpty()) {
            return false;
        }

        String currentHash = found.get().agentPassword();
        boolean unchanged = currentHash != null
                && passwordService.verify(plainTextPassword, currentHash);
        if (unchanged) {
            return true;
        }

        int updated = agentRepository.updatePassword(agentId, passwordService.hash(plainTextPassword));
        if (updated == 0) {
            return false;
        }

        // note: this logs the user out (forcing them to log back in)
        agentRepository.revokeTokens(agentId);
        sessionService.endAll(agentId);

        // clear the cached login credentials
        authorizationCache.invalidate(agentId);

        auditService.recordUpdate("agent", agentId,
                Map.of("agent_password", "[redacted]"),
                Map.of("agent_password", "[reset]"));
        return true;
    }

    /**
     * Changes the agent's job title. Spaces at either end are dropped.
     * False (404) for an unknown or removed agent, and for the SYSTEM agent.
     * Throws InvalidRequest (400, agent.title_blank on field title) for a blank title.
     */
    @Transactional
    public boolean setTitle(UUID agentId, String title) {
        String trimmed = title == null ? "" : title.strip();
        if (trimmed.isEmpty()) {
            throw InvalidRequest.onField("title", "agent.title_blank");
        }
        if (SystemPrincipal.AGENT_UUID.equals(agentId)) {
            return false;
        }

        Optional<AgentRow> before = agentRepository.findById(agentId);
        if (before.isEmpty()) {
            return false;
        }
        // the audit log only records real changes
        if (trimmed.equals(before.get().title())) {
            return true;
        }
        if (agentRepository.updateTitle(agentId, trimmed) == 0) {
            return false;
        }

        auditService.recordUpdate("agent", agentId,
                Map.of("title", before.get().title()),
                Map.of("title", trimmed));
        return true;
    }

    // Longest values the agent table holds (V3)
    private static final int WORK_PHONE_MAX = 20;
    private static final int HOURS_MAX = 200;

    /**
     * The agent changes their own work phone and the hours they can be reached, from their own page.
     * changes is keyed by column (work_phone, hours_available); a missing key is left alone.
     * Spaces at either end are dropped, and blank text clears the value.
     * Empty (404) for an unknown or removed agent, and for the SYSTEM agent.
     * Throws InvalidRequest (400) agent.work_phone_too_long on workPhone, agent.hours_too_long on hoursAvailable,
     * or agent.not_text when a value is not text.
     */
    @Transactional
    public Optional<Agent> patchOwn(UUID agentId, Map<String, Object> changes) {
        Map<String, Object> clean = new HashMap<>();
        if (changes.containsKey("work_phone")) {
            clean.put("work_phone", ownText(changes.get("work_phone"), "workPhone", WORK_PHONE_MAX, "agent.work_phone_too_long"));
        }
        if (changes.containsKey("hours_available")) {
            clean.put("hours_available", ownText(changes.get("hours_available"), "hoursAvailable", HOURS_MAX, "agent.hours_too_long"));
        }
        if (SystemPrincipal.AGENT_UUID.equals(agentId)) {
            return Optional.empty();
        }

        Optional<AgentRow> before = agentRepository.findById(agentId);
        if (before.isEmpty()) {
            return Optional.empty();
        }
        Optional<AgentRow> after = agentRepository.patchOwn(agentId, clean);
        if (after.isEmpty()) {
            return Optional.empty();
        }

        // Agent, not AgentRow, so the password hash never reaches the audit log
        var diff = AuditMapper.diff(before.get().toAgent(), after.get().toAgent());
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("agent", agentId, diff.before(), diff.after());
        }
        return Optional.of(after.get().toAgent());
    }

    // Text from the agent's own page: stripped, blank is null, and no longer than max
    private static String ownText(Object value, String field, int max, String tooLongCode) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw InvalidRequest.onField(field, "agent.not_text");
        }
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.length() > max) {
            throw InvalidRequest.onField(field, tooLongCode, max);
        }
        return stripped;
    }

    /**
     * Soft delete. Also ends every session the agent holds, the same as a password reset.
     * False (404) for an unknown or already removed agent, and for the SYSTEM agent.
     * Throws RuleConflict (409) when an agent tries to remove themself,
     * or to remove the last agent who holds the Admin role,
     * or (access.last_key) when it would leave nobody who can manage roles (see AccessKeys).
     */
    @Transactional
    public boolean removeAgent(UUID agentId, UUID removedBy) {
        if (SystemPrincipal.AGENT_UUID.equals(agentId)) {
            return false;
        }

        Optional<AgentRow> before = agentRepository.findById(agentId);
        if (before.isEmpty()) {
            return false;
        }

        if (agentId.equals(removedBy)) {
            throw RuleConflict.of("agent.cannot_remove_self");
        }

        if (grantedRoleService.isLastHolderOfRole(agentId, ADMIN_ROLE)) {
            throw RuleConflict.of("agent.last_admin");
        }

        // a removed agent holds nothing, so removing the last one with the keys is refused like any other loss of them
        return accessKeys.keep(() -> {
            if (!agentRepository.softDelete(agentId)) {
                return false;
            }
            sessionService.endAll(agentId);

            authorizationCache.invalidate(agentId);
            auditService.recordDelete("agent", agentId, AuditMapper.toMap(before.get().toAgent()));
            return true;
        });
    }

    /**
     * Empty for a wrong password and an unknown email alike. Throws LoginRefused when
     * the throttle turns the attempt away before any of this runs.
     */
    public Optional<AgentAuthResult> verifyLogin(String workEmail, String plainTextPassword, String userAgentHeader, String ipAddress){
        loginThrottle.admit(ipAddress, workEmail);

        Optional<AgentRow> agentRow = findByWorkEmailForAuth(workEmail);

        // runs even when there is no agent, so the time taken does not say whether the email exists
        boolean passwordCorrect = passwordService.verify(plainTextPassword,
                agentRow.map(AgentRow::agentPassword).orElse(null));

        if (agentRow.isEmpty()){
            return Optional.empty();
        }

        AgentRow ar = agentRow.get();
        UserAgent userAgent = UserAgent.parseUserAgentString(userAgentHeader);

        LoginEventRow loginEventRow = new LoginEventRow(
                ar.uuid(),
                OffsetDateTime.now(ZoneOffset.UTC),
                ipAddress,
                userAgent.getOperatingSystem().getName(),
                userAgent.getBrowser().getName(),
                passwordCorrect ? HttpStatus.OK.value() : HttpStatus.UNAUTHORIZED.value()
        );

        loginEventRepository.save(loginEventRow);

        if (passwordCorrect){
            loginThrottle.succeeded(ipAddress, workEmail);
            return personService.findByID(ar.personId())
                    .map(person -> {
                        AgentSessionService.Started started = sessionService.start(ar.uuid(), ipAddress,
                                userAgent.getBrowser().getName(), userAgent.getOperatingSystem().getName());
                        return issue(ar, person, started.sessionId(), started.refreshToken());
                    });
        }
        return Optional.empty();
    }

    /**
     * Trades a refresh token for a new access token and a new refresh token.
     * Empty when the session is over, for any reason. No password is checked here.
     */
    public Optional<AgentAuthResult> refreshSession(String refreshToken, String ipAddress) {
        Optional<AgentSessionService.Rotated> rotated = sessionService.rotate(refreshToken, ipAddress);
        if (rotated.isEmpty()) {
            return Optional.empty();
        }
        UUID agentId = rotated.get().agentId();

        // A removed agent, or a session older than a password reset, cannot go on.
        // Both paths already end sessions; this catches one that forgot to.
        Optional<AgentRow> agentRow = agentRepository.findById(agentId);
        OffsetDateTime validFrom = agentRow.map(AgentRow::tokensValidFrom).orElse(null);
        boolean startedBeforeReset = validFrom != null
                && rotated.get().sessionStartedAt().isBefore(validFrom);
        if (agentRow.isEmpty() || startedBeforeReset) {
            sessionService.endAll(agentId);
            return Optional.empty();
        }

        AgentRow ar = agentRow.get();
        return personService.findByID(ar.personId())
                .map(person -> issue(ar, person, rotated.get().sessionId(), rotated.get().refreshToken()));
    }

    /**
     * The agent's signed-in computers (sessions not ended), for the snapshot every request is checked with
     * (config AgentAuthorizationLoader). An access token from any other session is refused.
     */
    public Set<UUID> openSessionIds(UUID agentId) {
        return sessionService.notEndedIds(agentId);
    }

    /** Log out of this computer only. Unknown tokens are ignored. */
    public void endSession(String refreshToken) {
        sessionService.end(refreshToken);
    }

    /** The agent's signed-in computers. Empty for an unknown or removed agent (404). */
    public Optional<List<AgentSessionRow>> listSessions(UUID agentId) {
        return agentRepository.findById(agentId)
                .map(row -> sessionService.listOpen(agentId));
    }

    /**
     * An admin signs one of the agent's computers out. Its access token is refused on its very next request
     * (JwtAuthFilter checks the token's session), so Glade there goes to the sign-in screen.
     * False (404) when the session is not this agent's or has already ended.
     */
    @Transactional
    public boolean signOutComputer(UUID agentId, UUID sessionId) {
        if (!sessionService.endForAgent(agentId, sessionId)) {
            return false;
        }
        auditService.recordUpdate("agent_session", sessionId,
                Map.of("signed_in", true),
                Map.of("signed_in", false));
        return true;
    }

    /**
     * Signs out every computer the agent uses: every access token they hold is refused on its next request.
     * False (404) for an unknown or removed agent.
     */
    @Transactional
    public boolean signOutEverywhere(UUID agentId) {
        if (agentRepository.findById(agentId).isEmpty()) {
            return false;
        }
        int ended = sessionService.endAll(agentId);
        if (ended > 0) {
            auditService.recordUpdate("agent", agentId,
                    Map.of("signed_in_computers", ended),
                    Map.of("signed_in_computers", 0));
        }
        return true;
    }

    // The access token plus who the agent is and what they may do.
    private AgentAuthResult issue(AgentRow ar, Person person, UUID sessionId, String refreshToken) {
        AgentWithPerson agentWithPerson = new AgentWithPerson(ar.toAgent(), person);
        AgentIdentity identity = new AgentIdentity(
                agentWithPerson,
                grantedRoleService.findRoleNamesForAgent(ar.uuid()),
                permissionNamesForAgent(ar.uuid()));
        String token = jwtService.generateToken(agentWithPerson, sessionId);
        return new AgentAuthResult(identity, token, refreshToken);
    }

    /**
     * For GET /api/agents/self. The caller passes in the permissions from the request's
     * Authentication. That is the same set the 403 check uses, so the screen and the
     * server always agree.
     */
    public Optional<AgentIdentity> findSelf(UUID agentId, List<String> permissions) {
        return agentRepository.findById(agentId)
                .flatMap(row -> personService.findByID(row.personId())
                        .map(person -> new AgentIdentity(
                                new AgentWithPerson(row.toAgent(), person),
                                grantedRoleService.findRoleNamesForAgent(agentId),
                                permissions)));
    }

    // The same query AgentAuthorizationLoader runs to fill the 403 cache.
    // It skips revoked grants, revoked role permissions, deleted roles and denials.
    private List<String> permissionNamesForAgent(UUID agentId) {
        return permissionService.findPermissionsForAgent(agentId)
                .stream()
                .map(Permission::permissionName)
                .toList();
    }

    @Transactional
    public void ensureRootAgentExists(String email, String password) {
        UUID rootAgentId = findByWorkEmail(email)
                .map(Agent::uuid)
                .orElseGet(() -> createRootAgent(email, password));

        grantedRoleService.systemGrantRole(rootAgentId, ADMIN_ROLE);
    }

    private UUID createRootAgent(String email, String password) {
        UUID correlationId = UUID.randomUUID();

        Person person = personService.systemInsertRootPerson("Root Admin", correlationId);
        String hashed = passwordService.hash(password);

        AgentRow agentRow = new AgentRow(
                person.uuid(),
                null,
                email,
                hashed
        );

        Agent agent = agentRepository.save(agentRow).toAgent();
        log.warn("Root agent with email {} has been created - change password ASAP", email);
        return agent.uuid();
    }

    public Optional<Agent> findById(UUID uuid) {
        return agentRepository.findById(uuid).map(AgentRow::toAgent);
    }

    public Optional<Agent> findByWorkEmail(String workEmail){
        return agentRepository.findByWorkEmail(workEmail).map(AgentRow::toAgent);
    }

    Optional<AgentRow> findByWorkEmailForAuth(String workEmail){
        return agentRepository.findByWorkEmail(workEmail);
    }

    public List<LoginEventRow> getLoginEventsByAgentId(UUID agentId) {
        return loginEventRepository.getLoginEventsByAgentId(agentId);
    }
}
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

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);


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
            LoginThrottle loginThrottle
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

        // clear the cached login credentials
        authorizationCache.invalidate(agentId);

        auditService.recordUpdate("agent", agentId,
                Map.of("agent_password", "[redacted]"),
                Map.of("agent_password", "[reset]"));
        return true;
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
                        AgentWithPerson agentWithPerson = new AgentWithPerson(ar.toAgent(), person);
                        Set<Permission> permissions = permissionService.findPermissionsForAgent(ar.uuid());
                        String token = jwtService.generateToken(agentWithPerson, permissions);
                        return new AgentAuthResult(agentWithPerson, token);
                    });
        }
        return Optional.empty();
    }

    @Transactional
    public void ensureRootAgentExists(String email, String password) {
        UUID rootAgentId = findByWorkEmail(email)
                .map(Agent::uuid)
                .orElseGet(() -> createRootAgent(email, password));

        grantedRoleService.systemGrantRole(rootAgentId, "Admin");
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
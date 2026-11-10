package io.github.lordship.config;

import io.github.lordship.access.Agent;
import io.github.lordship.access.AgentService;
import io.github.lordship.access.Permission;
import io.github.lordship.access.PermissionService;
import io.github.lordship.identity.AgentAuthorization;
import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.propertyassignments.PropertyAssignmentService;
import io.github.lordship.shared.PropertyScope;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// currently built for Caffeine to keep a cache data needed on every request
@Component
public class AgentAuthorizationLoader {

    static final String ASSIGN_ALL = "assignments:assign-all";

    private final AgentService agentService;
    private final PermissionService permissionService;
    private final PropertyAssignmentService propertyAssignmentService;

    public AgentAuthorizationLoader(AgentService agentService, PermissionService permissionService, PropertyAssignmentService propertyAssignmentService) {
        this.agentService = agentService;
        this.permissionService = permissionService;
        this.propertyAssignmentService = propertyAssignmentService;
    }


    public AgentAuthorization load(UUID agentId) {
        Optional<Agent> found = agentService.findById(agentId);
        if (found.isEmpty()) {
            return null;
        }
        Agent agent = found.get();

        Set<String> permissions = permissionService.findPermissionsForAgent(agentId)
                .stream()
                .map(Permission::permissionName)
                .collect(Collectors.toUnmodifiableSet());

        // Resolved here rather than per request: it is a function of the permission
        // set and the assignment set, and both are already in hand.
        PropertyScope scope = permissions.contains(ASSIGN_ALL)
                ? new PropertyScope.All()
                : new PropertyScope.Restricted(propertyAssignmentService.getAgentAssignedPropertyUUIDs(agentId));

        return new AgentAuthorization(
                new AgentPrincipal(agent.uuid(), agent.personId(), scope),
                agent.tokensValidFrom(),
                permissions,
                agentService.openSessionIds(agentId)
        );
    }
}

package io.github.lordship.personnel.internal;

import java.util.List;
import java.util.UUID;

// GET /api/personnel -- the global view. An agent with several roles is under each one.
public record PersonnelResponse(
        List<RoleGroup> roles,
        List<AgentName> noRole
) {
    public record RoleGroup(
            UUID roleId,
            String roleName,
            String roleDescription,
            List<AgentName> agents
    ) { }

    public record AgentName(
            UUID agentId,
            String nameFull
    ) { }
}

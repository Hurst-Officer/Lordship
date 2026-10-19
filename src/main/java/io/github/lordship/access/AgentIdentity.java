package io.github.lordship.access;

import java.util.List;
import java.util.TreeSet;

// Who an agent is and what they may do.
// Backs the login response and GET /api/agents/self.
public record AgentIdentity(
        AgentWithPerson agentWithPerson,
        List<String> roles,
        List<String> permissions
) {

    // Both lists come out sorted with no duplicates.
    public AgentIdentity {
        roles = List.copyOf(new TreeSet<>(roles));
        permissions = List.copyOf(new TreeSet<>(permissions));
    }
}

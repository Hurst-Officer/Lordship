package io.github.lordship.identity;

import io.github.lordship.shared.PropertyScope;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

// openSessions: the agent's signed-in computers (agent_session ids not ended). An access token from any other
// session was signed out (one computer, every computer, or the worker's own sign-out) and is refused at once.
public record AgentAuthorization(
        AgentPrincipal principal,
        OffsetDateTime tokensValidFrom,
        Set<String> permissions,
        Set<UUID> openSessions
) {

    public AgentAuthorization {
        permissions = Set.copyOf(permissions);
        openSessions = Set.copyOf(openSessions);

        // has to branch b/c the
        if (principal.propertyScope() instanceof PropertyScope.Restricted(var propertyIds)) {
            principal = new AgentPrincipal(
              principal.agentUuid(),
              principal.personUuid(),
              new PropertyScope.Restricted(Set.copyOf(propertyIds))
            );
        }
    }
}

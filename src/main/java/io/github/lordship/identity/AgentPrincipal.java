package io.github.lordship.identity;

import io.github.lordship.shared.PropertyScope;
import org.springframework.security.core.Authentication;

import java.util.Set;
import java.util.UUID;

public record AgentPrincipal (
        UUID agentUuid,
        UUID personUuid,
        PropertyScope propertyScope
) implements LordshipPrincipal {

    // The properties the signed-in agent may see. Anyone who is not an agent sees none.
    public static PropertyScope scopeOf(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AgentPrincipal agent
                ? agent.propertyScope()
                : new PropertyScope.Restricted(Set.of());
    }
}

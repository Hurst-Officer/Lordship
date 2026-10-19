package io.github.lordship.access;

import java.util.List;
import java.util.UUID;

// The body of GET /api/agents/self. Same as AgentLoginResponse, without the token.
public record AgentSelfResponse(
        UUID id,
        String workEmail,
        String nameFull,
        List<String> roles,
        List<String> permissions
) {
    public static AgentSelfResponse from(AgentIdentity identity) {
        return new AgentSelfResponse(
                identity.agentWithPerson().agent().uuid(),
                identity.agentWithPerson().agent().workEmail(),
                identity.agentWithPerson().person().nameFull(),
                identity.roles(),
                identity.permissions()
        );
    }
}

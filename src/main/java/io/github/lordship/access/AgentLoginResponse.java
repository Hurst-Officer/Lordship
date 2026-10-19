package io.github.lordship.access;

import java.util.List;
import java.util.UUID;

public record AgentLoginResponse(
        UUID id,
        String workEmail,
        String nameFull,
        List<String> roles,
        List<String> permissions,
        String token
)

{
    public static AgentLoginResponse from(AgentIdentity identity, String token) {
        return new AgentLoginResponse(
                identity.agentWithPerson().agent().uuid(),
                identity.agentWithPerson().agent().workEmail(),
                identity.agentWithPerson().person().nameFull(),
                identity.roles(),
                identity.permissions(),
                token
        );
    }
}

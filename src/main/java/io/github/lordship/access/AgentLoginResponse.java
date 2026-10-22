package io.github.lordship.access;

import java.util.List;
import java.util.UUID;

public record AgentLoginResponse(
        UUID id,
        String workEmail,
        String nameFull,
        List<String> roles,
        List<String> permissions,
        String token,
        String refreshToken
)

{
    public static AgentLoginResponse from(AgentAuthResult result) {
        AgentIdentity identity = result.identity();
        return new AgentLoginResponse(
                identity.agentWithPerson().agent().uuid(),
                identity.agentWithPerson().agent().workEmail(),
                identity.agentWithPerson().person().nameFull(),
                identity.roles(),
                identity.permissions(),
                result.token(),
                result.refreshToken()
        );
    }
}

package io.github.lordship.access;

import java.util.UUID;

public record AgentLoginResponse(
        UUID id,
        String workEmail,
        String nameFull,
        String token
)

{
    public static AgentLoginResponse from(AgentWithPerson agentWithPerson, String token) {
        return new AgentLoginResponse(
                agentWithPerson.agent().uuid(),
                agentWithPerson.agent().workEmail(),
                agentWithPerson.person().nameFull(),
                token
        );
    }
}

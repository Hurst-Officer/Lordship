package io.github.lordship.access.internal.agents;


import io.github.lordship.access.AgentWithPerson;

import java.util.UUID;

public record AgentRegistrationResponse(UUID id,
                                        String workEmail,
                                        String nameFull
) {

    public static AgentRegistrationResponse from(AgentWithPerson agentWithPerson){
        return new AgentRegistrationResponse(
                agentWithPerson.agent().uuid(),
                agentWithPerson.agent().workEmail(),
                agentWithPerson.person().nameFull()
        );
    }
}

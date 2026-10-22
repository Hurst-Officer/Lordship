package io.github.lordship.access.internal.agents;

import io.github.lordship.access.Agent;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentRow (
        UUID uuid,
        UUID personId,
        String workPhone,
        String workEmail,
        String title,
        String agentPassword,
        OffsetDateTime tokensValidFrom,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {
    public Agent toAgent(){
        return new Agent(
                this.uuid,
                this.personId,
                this.workPhone,
                this.workEmail,
                this.title,
                this.tokensValidFrom,
                this.createdAt,
                this.deletedAt
        );
    }

    // title is left to the database default ("Agent")
    public AgentRow(UUID personId, String workPhone, String workEmail, String agentPassword){
        this(
                null,
                personId,
                workPhone,
                workEmail,
                null,
                agentPassword,
                null,
                null,
                null
        );
    }
}
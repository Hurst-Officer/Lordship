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
        String hoursAvailable,
        // the color coworkers see this agent in (one of AgentColors.NAMES), or null until the agent picks one
        String color,
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
                this.hoursAvailable,
                this.color,
                this.tokensValidFrom,
                this.createdAt,
                this.deletedAt
        );
    }

    // title is left to the database default ("Agent"), hours available and color to null
    public AgentRow(UUID personId, String workPhone, String workEmail, String agentPassword){
        this(
                null,
                personId,
                workPhone,
                workEmail,
                null,
                null,
                null,
                agentPassword,
                null,
                null,
                null
        );
    }
}

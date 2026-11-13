package io.github.lordship.access;

import java.time.OffsetDateTime;
import java.util.UUID;


public record Agent(
    UUID uuid,
    UUID personId,
    String workPhone,
    String workEmail,
    String title,
    String hoursAvailable,
    // The color coworkers see this agent in, in Glade (their picked lots, their initials). One of AgentColors.NAMES,
    // or null until the agent picks one; Glade then gives the agent a color of the same set by the agent's id.
    String color,
    OffsetDateTime tokensValidFrom,
    OffsetDateTime createdAt,
    OffsetDateTime deletedAt
) {

    // An agent who has not picked a color
    public Agent(UUID uuid, UUID personId, String workPhone, String workEmail, String title, String hoursAvailable,
                 OffsetDateTime tokensValidFrom, OffsetDateTime createdAt, OffsetDateTime deletedAt) {
        this(uuid, personId, workPhone, workEmail, title, hoursAvailable, null, tokensValidFrom, createdAt, deletedAt);
    }

    public boolean isSoftDeleted() {
        return deletedAt != null;
    }

}

package io.github.lordship.access;

import java.time.OffsetDateTime;
import java.util.UUID;


public record Agent(
    UUID uuid,
    UUID personId,
    String workPhone,
    String workEmail,
    String title,
    OffsetDateTime tokensValidFrom,
    OffsetDateTime createdAt,
    OffsetDateTime deletedAt
) {

    public boolean isSoftDeleted() {
        return deletedAt != null;
    }

}

package io.github.lordship.homes.internal;

import io.github.lordship.audit.History;
import io.github.lordship.homes.Home;

import java.time.OffsetDateTime;
import java.util.UUID;

// One row of a lot's deleted homes (GET /api/homes/deleted).
// deletedBy is the agent's full name from the audit log, or null without audit:view.
public record DeletedHomeResponse(
        UUID id,
        UUID lotId,
        String name,
        String vin,
        String make,
        String model,
        OffsetDateTime deletedAt,
        String deletedBy
) {

    public static DeletedHomeResponse from(Home home, History.Mark deletion) {
        return new DeletedHomeResponse(
                home.uuid(),
                home.lotId(),
                home.name(),
                home.vin(),
                home.make(),
                home.model(),
                home.deletedAt(),
                deletion == null ? null : deletion.name()
        );
    }
}

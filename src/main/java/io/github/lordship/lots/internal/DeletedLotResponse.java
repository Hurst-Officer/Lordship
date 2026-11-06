package io.github.lordship.lots.internal;

import io.github.lordship.audit.History;
import io.github.lordship.lots.Lot;

import java.time.OffsetDateTime;
import java.util.UUID;

// One row of a property's deleted lots (GET /api/lots/deleted).
// deletedBy is the agent's full name from the audit log, or null without audit:view.
public record DeletedLotResponse(
        UUID id,
        UUID propertyId,
        String lotNumber,
        String lotAddress,
        OffsetDateTime deletedAt,
        String deletedBy
) {

    public static DeletedLotResponse from(Lot lot, History.Mark deletion) {
        return new DeletedLotResponse(
                lot.uuid(),
                lot.propertyId(),
                lot.lotNumber(),
                lot.lotAddress(),
                lot.deletedAt(),
                deletion == null ? null : deletion.name()
        );
    }
}

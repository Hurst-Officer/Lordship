package io.github.lordship.meters;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A submeter hangs off this main from effectiveFrom until the day before effectiveTo (null: still does). */
public record SubmeterMainLink(
        UUID uuid,
        UUID submeterId,
        UUID mainId,
        UUID propertyId,
        MeterType utilityType,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {}

package io.github.lordship.meters;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A submeter feeds this lot from effectiveFrom until the day before effectiveTo (null: still does).
 * A lot has one submeter per utility on any day.
 */
public record SubmeterLotLink(
        UUID uuid,
        UUID submeterId,
        UUID lotId,
        UUID propertyId,
        MeterType utilityType,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {}

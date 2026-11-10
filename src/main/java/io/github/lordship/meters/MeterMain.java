package io.github.lordship.meters;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A meter a utility company bills the park for. It belongs to the property, never to a lot.
 * How to read its dial (unit, multiplier, dial_max) is in its dated {@link MeterSetting}s.
 */
public record MeterMain(
        UUID uuid,
        UUID propertyId,
        MeterType utilityType,
        UUID rateGroupId,
        String title,
        String provider,
        String accountNumber,
        String serialNumber,
        Double pointX,
        Double pointY,
        LocalDate installedOn,
        LocalDate removedOn,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt
) {}

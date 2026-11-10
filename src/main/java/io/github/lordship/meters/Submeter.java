package io.github.lordship.meters;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The park's own meter. It hangs off one main and feeds one or more lots.
 * How to read its dial is in its dated {@link MeterSetting}s.
 */
public record Submeter(
        UUID uuid,
        UUID propertyId,
        MeterType utilityType,
        String title,
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

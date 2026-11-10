package io.github.lordship.meters;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One read of a main or of a submeter (exactly one of mainId, submeterId is set). */
public record MeterRead(
        UUID uuid,
        UUID mainId,
        UUID submeterId,
        LocalDate readOn,
        Long reading,
        MeterReadKind kind,
        MeterReadSource source,
        Boolean isEstimated,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt
) {}

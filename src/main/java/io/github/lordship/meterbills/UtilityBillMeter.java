package io.github.lordship.meterbills;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.lordship.meters.MeterUnit;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A main a bill is for, with the old and new reads and the usage printed for it. */
public record UtilityBillMeter(
        UUID uuid,
        UUID billId,
        UUID mainId,
        UUID propertyId,
        UUID priorReadId,
        UUID currentReadId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal printedUsage,
        MeterUnit printedUnit,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {}

package io.github.lordship.meters.internal;
import io.github.lordship.meters.MeterRead;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MeterReadResponse(
        UUID id,
        UUID targetedMeterId,
        Integer meterAmount,
        OffsetDateTime readAt,
        Boolean isEstimated,
        Integer rolloverCount
) {

    public static io.github.lordship.meters.internal.MeterReadResponse from(MeterRead mr) {
        return new io.github.lordship.meters.internal.MeterReadResponse(
                mr.uuid(),
                mr.targetedMeter(),
                mr.meterAmount(),
                mr.readAt(),
                mr.isEstimated(),
                mr.rolloverCount()
        );
    }
}
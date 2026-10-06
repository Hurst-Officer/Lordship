package io.github.lordship.meters.internal;

import io.github.lordship.meters.MeterRelation;

import java.time.LocalDate;
import java.util.UUID;

public record MeterRelationResponse(
        UUID id,
        UUID parentMeterId,
        UUID childMeterId,
        Boolean hasUnmeteredRemainder,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {

    public static io.github.lordship.meters.internal.MeterRelationResponse from(MeterRelation ml) {
        return new io.github.lordship.meters.internal.MeterRelationResponse(
                ml.uuid(),
                ml.parentMeter(),
                ml.childMeter(),
                ml.hasUnmeteredRemainder(),
                ml.effectiveFrom(),
                ml.effectiveTo()
        );
    }
}
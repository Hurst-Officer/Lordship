package io.github.lordship.meters.internal;

import io.github.lordship.meters.MeterMeasurement;
import io.github.lordship.meters.MeterType;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record MeterRelationCreateRequest(
        @NotNull
        UUID parentMeterId,

        @NotNull
        UUID childMeterId,

        @NotNull
        Boolean hasUnmeteredRemainder,

        @NotNull
        LocalDate effectiveFrom
) {
}
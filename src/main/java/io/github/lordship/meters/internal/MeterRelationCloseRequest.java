package io.github.lordship.meters.internal;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record MeterRelationCloseRequest(
        @NotNull
        UUID parentMeterId,

        @NotNull
        UUID childMeterId,

        @NotNull
        Boolean hasUnmeteredRemainder,

        @NotNull
        LocalDate effectiveFrom,

        @NotNull
        LocalDate effectiveTo
) {
}

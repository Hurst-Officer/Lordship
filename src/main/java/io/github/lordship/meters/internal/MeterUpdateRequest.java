package io.github.lordship.meters.internal;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record MeterUpdateRequest(
        @NotNull
        UUID id,
        UUID meterId,
        String title,
        String description,
        String serialNumber
) {
}

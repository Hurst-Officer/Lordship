package io.github.lordship.vehicles.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record VehicleCreateRequest (
        @NotNull
        UUID tenancyId,

        @NotBlank
        String plateNumber
) {}

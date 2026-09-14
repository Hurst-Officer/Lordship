package io.github.lordship.tenants.internal;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record OccupantCreateRequest(
        @NotNull
        UUID tenancyId,

        @NotNull
        UUID personId,

        // Optional, ISO yyyy-MM-dd. Omitted, it takes the same default a tenant
        // does, so a household added in one sitting shares one start date.
        LocalDate startDate
) { }
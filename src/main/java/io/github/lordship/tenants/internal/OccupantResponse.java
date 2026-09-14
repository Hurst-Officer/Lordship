package io.github.lordship.tenants.internal;

import io.github.lordship.tenants.Occupant;

import java.time.LocalDate;
import java.util.UUID;

public record OccupantResponse(
        UUID uuid,
        UUID tenancyId,
        UUID personId,
        LocalDate startDate,
        LocalDate endDate
) {

    public static OccupantResponse from(Occupant o) {
        return new OccupantResponse(
                o.uuid(),
                o.tenancyId(),
                o.personId(),
                o.startDate(),
                o.endDate()
        );
    }
}
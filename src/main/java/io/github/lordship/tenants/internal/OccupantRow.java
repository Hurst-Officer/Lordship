package io.github.lordship.tenants.internal;

import io.github.lordship.tenants.Occupant;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record OccupantRow(
        UUID uuid,
        UUID tenancyId,
        UUID personId,
        LocalDate startDate,
        LocalDate endDate,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {

    public Occupant toOccupant() {
        return new Occupant(
                this.uuid,
                this.tenancyId,
                this.personId,
                this.startDate,
                this.endDate,
                this.createdAt,
                this.deletedAt
        );
    }
}
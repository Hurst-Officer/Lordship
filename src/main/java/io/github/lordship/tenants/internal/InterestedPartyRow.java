package io.github.lordship.tenants.internal;

import io.github.lordship.tenants.InterestedParty;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record InterestedPartyRow(
        UUID uuid,
        UUID tenancyId,
        UUID personId,
        String notificationReason,
        LocalDate startDate,
        LocalDate endDate,
        boolean acceptPayments,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {
    public InterestedParty toInterestedParty() {
        return new InterestedParty(
                this.uuid,
                this.tenancyId,
                this.personId,
                this.notificationReason,
                this.startDate,
                this.endDate,
                this.acceptPayments,
                this.notes,
                this.createdAt,
                this.deletedAt
        );
    }
}
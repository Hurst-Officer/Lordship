package io.github.lordship.homes.internal;

import io.github.lordship.homes.SecuredParty;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SecuredPartyRow(
        UUID uuid,
        UUID mobileHomeId,
        UUID personId,
        LocalDate startDate,
        LocalDate endDate,
        boolean acceptPayments,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {
    public SecuredParty toSecuredParty() {
        return new SecuredParty(
                this.uuid,
                this.mobileHomeId,
                this.personId,
                this.startDate,
                this.endDate,
                this.acceptPayments,
                this.createdAt,
                this.deletedAt
        );
    }
}

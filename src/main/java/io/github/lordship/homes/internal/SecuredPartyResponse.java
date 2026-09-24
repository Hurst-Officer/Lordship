package io.github.lordship.homes.internal;

import io.github.lordship.homes.SecuredParty;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SecuredPartyResponse(
        UUID uuid,
        UUID mobileHomeId,
        UUID personId,
        LocalDate startDate,
        LocalDate endDate,
        boolean acceptPayments,
        OffsetDateTime createdAt
) {

    public static SecuredPartyResponse from(SecuredParty securedParty) {
        return new SecuredPartyResponse(
                securedParty.uuid(),
                securedParty.mobileHomeId(),
                securedParty.personId(),
                securedParty.startDate(),
                securedParty.endDate(),
                securedParty.acceptPayments(),
                securedParty.createdAt()
        );
    }
}

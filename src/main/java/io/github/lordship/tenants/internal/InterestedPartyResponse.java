package io.github.lordship.tenants.internal;

import io.github.lordship.tenants.InterestedParty;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record InterestedPartyResponse(
        UUID uuid,
        UUID tenancyId,
        UUID personId,
        String notificationReason,
        LocalDate startDate,
        LocalDate endDate,
        boolean acceptPayments,
        String notes,
        OffsetDateTime createdAt
) {

    public static  InterestedPartyResponse from(InterestedParty interestedParty) {
        return new InterestedPartyResponse(
                interestedParty.uuid(),
                interestedParty.tenancyId(),
                interestedParty.personId(),
                interestedParty.notificationReason(),
                interestedParty.startDate(),
                interestedParty.endDate(),
                interestedParty.acceptPayments(),
                interestedParty.notes(),
                interestedParty.createdAt()
        );
    }
}

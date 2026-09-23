package io.github.lordship.tenants;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

// An interested party has a legal right to be informed about what happens to the tenancy
public record InterestedParty (
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
) { }

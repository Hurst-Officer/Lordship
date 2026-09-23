package io.github.lordship.tenancy.internal;

import io.github.lordship.tenancy.Tenancy;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TenancyResponse(
        UUID uuid,
        UUID lotId,
        LocalDate startDate,
        LocalDate endDate,
        boolean noPersonalChecks,
        boolean noPartialPayments,
        boolean acceptPayments,
        boolean exemptFromLateFees,
        String notes,
        OffsetDateTime createdAt
) {

    public static TenancyResponse from(Tenancy t) {
        return new TenancyResponse(
                t.uuid(),
                t.lotId(),
                t.startDate(),
                t.endDate(),
                t.noPersonalChecks(),
                t.noPartialPayments(),
                t.acceptPayments(),
                t.exemptFromLateFees(),
                t.notes(),
                t.createdAt()
        );
    }

}
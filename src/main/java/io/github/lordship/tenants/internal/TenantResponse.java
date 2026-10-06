package io.github.lordship.tenants.internal;

import io.github.lordship.tenants.Tenant;

import java.time.LocalDate;
import java.util.UUID;

public record TenantResponse(
        UUID id,
        UUID tenancyId,
        UUID personId,
        LocalDate startDate,
        LocalDate endDate
) {

    public static TenantResponse from(Tenant t) {
        return new TenantResponse(
                t.uuid(),
                t.tenancyId(),
                t.personId(),
                t.startDate(),
                t.endDate()
        );
    }

}
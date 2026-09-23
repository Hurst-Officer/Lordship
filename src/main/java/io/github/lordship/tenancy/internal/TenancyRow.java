package io.github.lordship.tenancy.internal;

import io.github.lordship.tenancy.Tenancy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TenancyRow(
    UUID uuid,
    UUID lotId,
    LocalDate startDate,
    LocalDate endDate,
    boolean noPersonalChecks,
    boolean noPartialPayments,
    boolean acceptPayments,
    boolean exemptFromLateFees,
    String notes,
    OffsetDateTime createdAt,
    OffsetDateTime deletedAt
) {

    public Tenancy toTenancy(){
        return new Tenancy(
                this.uuid,
                this.lotId,
                this.startDate,
                this.endDate,
                this.noPersonalChecks,
                this.noPartialPayments,
                this.acceptPayments,
                this.exemptFromLateFees,
                this.notes,
                this.createdAt,
                this.deletedAt
        );
    }
}

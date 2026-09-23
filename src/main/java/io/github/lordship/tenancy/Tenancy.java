package io.github.lordship.tenancy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Tenancy(
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
    public boolean isSoftDeleted() {
        return deletedAt != null;
    }
}
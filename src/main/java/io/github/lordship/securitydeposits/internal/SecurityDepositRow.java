package io.github.lordship.securitydeposits.internal;

import io.github.lordship.securitydeposits.SecurityDeposit;
import io.github.lordship.securitydeposits.SecurityDepositSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

// Component order matches the column order in V10__deposits.sql.
public record SecurityDepositRow(
        UUID uuid,
        UUID tenancy,
        UUID instrument,
        SecurityDepositSource source,
        BigDecimal amount,
        LocalDate collectedOn,
        LocalDate settledOn,
        String description,
        String note,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {
    public SecurityDeposit toSecurityDeposit() {
        return new SecurityDeposit(
                uuid, tenancy, instrument, source, amount, collectedOn, settledOn,
                description, note, createdBy, createdAt, deletedAt);
    }

    // The minimum: whose it is, how much, when, and which kind of paper put it
    // there. Everything else arrives by PATCH.
    public static SecurityDepositRow forInsert(UUID tenancy,
                                               BigDecimal amount,
                                               SecurityDepositSource source,
                                               LocalDate collectedOn,
                                               UUID createdBy) {
        return new SecurityDepositRow(
                null, tenancy, null, source, amount,
                collectedOn, null,          // collectedOn, settledOn
                null, null,          // description, note
                createdBy, null, null);
    }
}
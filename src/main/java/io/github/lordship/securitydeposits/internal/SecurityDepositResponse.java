package io.github.lordship.securitydeposits.internal;

import io.github.lordship.securitydeposits.SecurityDeposit;
import io.github.lordship.securitydeposits.SecurityDepositSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SecurityDepositResponse(
        UUID uuid,
        UUID tenancy,
        UUID instrument,
        SecurityDepositSource source,
        BigDecimal amount,
        LocalDate collectedOn,
        LocalDate settledOn,
        boolean held, // true while settledOn is null -- still ours to give back
        String description,
        String note,
        UUID createdBy,
        OffsetDateTime createdAt
) {
    public static SecurityDepositResponse from(SecurityDeposit deposit) {
        return new SecurityDepositResponse(
                deposit.uuid(), deposit.tenancy(), deposit.instrument(), deposit.source(),
                deposit.amount(), deposit.collectedOn(), deposit.settledOn(), deposit.isHeld(),
                deposit.description(), deposit.note(), deposit.createdBy(), deposit.createdAt());
    }
}
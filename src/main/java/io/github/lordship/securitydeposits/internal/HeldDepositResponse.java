package io.github.lordship.securitydeposits.internal;

import io.github.lordship.securitydeposits.HeldDeposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record HeldDepositResponse(
        UUID id,
        UUID tenancyId,
        UUID lotId,
        String lotNumber,
        BigDecimal amount,
        LocalDate collectedOn,
        LocalDate tenancyEndedOn,
        Integer daysSinceTenancyEnded
) {

    public static HeldDepositResponse from(HeldDeposit deposit) {
        return new HeldDepositResponse(
                deposit.uuid(),
                deposit.tenancy(),
                deposit.lot(),
                deposit.lotNumber(),
                deposit.amount(),
                deposit.collectedOn(),
                deposit.tenancyEndedOn(),
                deposit.daysSinceTenancyEnded()
        );
    }

}

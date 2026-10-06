package io.github.lordship.accounts.internal;

import io.github.lordship.accounts.Account;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        UUID tenancyId,
        String accountStatus,
        BigDecimal balanceCached,
        boolean autopayEnabled,
        String notes,
        LocalDateTime createdAt
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.uuid(),
                account.tenancyId(),
                account.accountStatus().name(),
                account.balanceCached(),
                account.autopayEnabled(),
                account.notes(),
                account.createdAt()
        );
    }
}

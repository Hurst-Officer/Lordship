package io.github.lordship.securitydeposits;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One sum of money held on behalf of a tenancy. Not revenue and not a
 * transaction: a deposit must never move account.balance_cached, or an
 * invoice shows the tenant paid up when they are not.
 *
 * <p>A tenancy may hold several. A deposit paid over two months is two rows,
 * and what is still owed is the charge term's figure less the sum of them.
 */
public record SecurityDeposit(
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
    /** Still ours to give back, and still a line on the rent roll. */
    public boolean isHeld() {
        return settledOn == null && deletedAt == null;
    }

    public boolean isSoftDeleted() {
        return deletedAt != null;
    }
}
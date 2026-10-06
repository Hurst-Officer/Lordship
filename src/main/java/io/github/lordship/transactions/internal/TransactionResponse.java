package io.github.lordship.transactions.internal;

import io.github.lordship.transactions.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID accountId,
        String type,
        BigDecimal amount,
        String description,
        LocalDate billingPeriod,
        OffsetDateTime postedAt,
        OffsetDateTime deletedAt
) {
    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.uuid(),
                transaction.accountId(),
                transaction.type().name(),
                transaction.amount(),
                transaction.description(),
                transaction.billingPeriod(),
                transaction.postedAt(),
                transaction.deletedAt()
        );
    }
}

package io.github.lordship.homes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

// A secured party is someone with a legal claim on collateral (owner/lender/creditor)
public record SecuredParty(
        UUID uuid,
        UUID mobileHomeId,
        UUID personId,
        LocalDate startDate,
        LocalDate endDate,
        boolean acceptPayments,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {
    public boolean isSoftDeleted() {
        return deletedAt != null;
    }
}

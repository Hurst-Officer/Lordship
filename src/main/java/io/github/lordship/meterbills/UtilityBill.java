package io.github.lordship.meterbills;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One bill the park received, with what is printed on it.
 *
 * @param rateGroupId  the rate group it was entered under; null for the park's default.
 *                     Kept, so moving a main to another group later does not move old bills.
 * @param documentFile the scanned bill, once uploads come through the document reader
 */
public record UtilityBill(
        UUID uuid,
        UUID propertyId,
        UUID rateGroupId,
        String provider,
        String accountNumber,
        LocalDate statementDate,
        LocalDate periodStart,
        LocalDate periodEnd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal printedTotal,
        UUID documentFile,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt
) {}

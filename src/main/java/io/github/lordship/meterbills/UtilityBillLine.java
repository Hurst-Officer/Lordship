package io.github.lordship.meterbills;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.lordship.meters.MeterUnit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A line as printed on a bill: usage, base charge, ambulance fee, late fee...
 *
 * @param passedOn     whether it goes into the rate tenants pay (a late fee the park incurred would not)
 * @param billingMonth the rent month this line's charge lands in (the 1st); water and sewer on one bill may differ
 */
public record UtilityBillLine(
        UUID uuid,
        UUID billId,
        UtilityService service,
        String description,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal printedRate,
        MeterUnit rateUnit,
        Boolean passedOn,
        LocalDate billingMonth,
        Integer sortOrder,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {}

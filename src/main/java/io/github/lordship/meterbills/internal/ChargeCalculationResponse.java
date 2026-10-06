package io.github.lordship.meterbills.internal;

import io.github.lordship.meterbills.ChargeCalculation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ChargeCalculationResponse(
        UUID lotMeterId,
        UUID parentMeterId,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal usageAmount,
        BigDecimal rateApplied,
        BigDecimal calculatedAmount,
        Boolean startReadEstimated,
        Boolean endReadEstimated
) {

    public static ChargeCalculationResponse from(ChargeCalculation charge) {
        return new ChargeCalculationResponse(
                charge.lotMeter(),
                charge.parentMeter(),
                charge.periodStart(),
                charge.periodEnd(),
                charge.usageAmount(),
                charge.rateApplied(),
                charge.calculatedAmount(),
                charge.startReadEstimated(),
                charge.endReadEstimated()
        );
    }

}

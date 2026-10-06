package io.github.lordship.meterbills.internal;

import io.github.lordship.meterbills.MeterBills;
import io.github.lordship.meters.MeterMeasurement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record MeterBillsResponse(
        UUID id,
        UUID billedMeterId,
        BigDecimal billedAmount,
        BigDecimal rateAmount,
        MeterMeasurement rateUnit,
        LocalDate periodStart,
        LocalDate periodEnd
) {

    public static io.github.lordship.meterbills.internal.MeterBillsResponse from(MeterBills mb) {
        return new io.github.lordship.meterbills.internal.MeterBillsResponse(
                mb.uuid(),
                mb.billedMeter(),
                mb.billedAmount(),
                mb.rateAmount(),
                mb.rateUnit(),
                mb.periodStart(),
                mb.periodEnd()
        );
    }
}

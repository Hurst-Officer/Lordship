package io.github.lordship.meterbills;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.lordship.meters.MeterRead;

import java.math.BigDecimal;
import java.util.List;

/**
 * A bill with its meters and lines, and the checks that catch a typo: whether
 * the lines add up to the printed total, and whether each main's reads agree
 * with the usage printed beside them.
 *
 * @param totalMatches null when the bill has no printed total
 */
public record BillDetail(
        UtilityBill bill,
        List<Meter> meters,
        List<UtilityBillLine> lines,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal linesTotal,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal passedOnTotal,
        Boolean totalMatches
) {

    /**
     * @param readsBaseUsage   gallons or kWh between the old and new read; null without both reads, or when they cannot be counted
     * @param printedBaseUsage the printed usage in gallons or kWh
     * @param baseUsage        what the rate uses: the printed usage when there is one, else the reads'
     * @param usageMatches     null unless both are known; false when they differ by more than 1%
     *                         and by more than half of one printed unit (the provider's rounding)
     */
    public record Meter(
            UtilityBillMeter meter,
            String mainTitle,
            MeterRead priorRead,
            MeterRead currentRead,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal readsBaseUsage,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal printedBaseUsage,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal baseUsage,
            Boolean usageMatches
    ) {}
}

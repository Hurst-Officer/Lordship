package io.github.lordship.meters;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One read and what it says since the read before.
 *
 * @param unit      the unit usage is in: the meter's setting on the read's day
 * @param usage     in that unit (dial difference x multiplier); null when it cannot be counted
 * @param baseUsage the same in gallons (water) or kWh (power)
 */
public record ReadInterval(
        MeterRead read,
        LocalDate previousReadOn,
        Long previousReading,
        MeterUnit unit,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal usage,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal baseUsage,
        List<ReadFlag> flags
) {}

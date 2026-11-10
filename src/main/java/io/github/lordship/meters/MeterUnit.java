package io.github.lordship.meters;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What a meter or a bill counts in. Before any money is worked out, water is
 * converted to gallons and power stays in kWh, so a main read in hundreds of
 * cubic feet and a submeter read in gallons can be compared and priced alike.
 *
 * <p>A cubic foot is exactly 1728 / 231 gallons (a US gallon is 231 cubic
 * inches), so CF and CCF are kept as fractions rather than as 7.48 or 748.
 */
public enum MeterUnit {
    GAL(MeterType.WATER, 1, 1),
    KGAL(MeterType.WATER, 1000, 1),
    CF(MeterType.WATER, 1728, 231),
    CCF(MeterType.WATER, 172800, 231),
    KWH(MeterType.POWER, 1, 1);

    /** Base-unit amounts keep this many decimals */
    public static final int BASE_SCALE = 6;

    private final MeterType type;
    private final BigDecimal numerator;
    private final BigDecimal denominator;

    MeterUnit(MeterType type, long numerator, long denominator) {
        this.type = type;
        this.numerator = BigDecimal.valueOf(numerator);
        this.denominator = BigDecimal.valueOf(denominator);
    }

    public boolean suits(MeterType meterType) {
        return type == meterType;
    }

    /** An amount in this unit, as gallons (water) or kWh (power) */
    public BigDecimal toBase(BigDecimal amount) {
        return amount.multiply(numerator).divide(denominator, BASE_SCALE, RoundingMode.HALF_UP);
    }
}

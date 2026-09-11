package io.github.lordship.shared;

import java.math.BigDecimal;

public enum SecurityDepositMethod {
    NONE, FLAT, MULTIPLE_OF_RENT;

    /** Above this a "multiplier" is almost certainly dollars typed into the wrong mode. */
    public static final BigDecimal MAX_MONTHS = new BigDecimal("5");

    public boolean requiresAmount() {
        return this == FLAT || this == MULTIPLE_OF_RENT;
    }
}

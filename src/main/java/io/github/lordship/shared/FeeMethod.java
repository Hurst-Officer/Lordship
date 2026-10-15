package io.github.lordship.shared;

public enum FeeMethod {
    NONE, FLAT, PERCENT_OF_RENT, BANK_OR_FLAT, TIERED;
    // bank_or_flat: either the amt charged to us by the bank or a flat amt, whichever is greater
    // tiered: the fee depends on the day the payment arrives. The chart lives in late_fee_tiers (see LateFeeTier).

    public boolean requiresAmount() {
        return this == FLAT;
    }
}
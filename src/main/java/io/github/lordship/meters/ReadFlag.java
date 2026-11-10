package io.github.lordship.meters;

/** Why a read deserves a second look before anyone is billed from it. */
public enum ReadFlag {
    /** No earlier read, and the read is not marked START: there is nothing to count usage from */
    NO_PREVIOUS,
    /** Lower than the read before, and not marked ROLLOVER: a typo, or a meter swapped without a START */
    BACKWARDS,
    /** The same as the read before: a stuck meter, an empty home, or the old number copied */
    NO_CHANGE,
    /** Marked ROLLOVER but not lower than the read before: the dial would have gone round more than once. No usage is counted */
    ROLLOVER_UNLIKELY,
    /** More than three times the meter's usual use per day: a leak, or a misread */
    HIGH_USAGE,
    /** Higher than the dial can show (by the setting on its day): dial_max is wrong, or the read is. A rollover across it counts nothing */
    ABOVE_DIAL,
    /** The provider or the reader estimated it */
    ESTIMATED
}

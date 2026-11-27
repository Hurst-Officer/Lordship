package io.github.lordship.shared;

import java.time.LocalDate;

/**
 * When a new lease's rent starts, a term of the deal (terms_template.rent_starts), copied onto every charge term
 * of a lease so the lease keeps the rule the lease was signed under.
 *
 * <p>The day the household takes the home (tenancy.start_date) may be any day. The rent and the lease start on the
 * day this rule gives. Other ways (rent from the day the home is taken, a prorated first month) are kept for
 * later: each would be another value here, with the proration worked out in one place when billing needs one.
 */
public enum RentStarts {
    /**
     * The 1st of the month after the household takes the home, or that day when the household takes the home on a
     * 1st. Nothing is prorated: the household moving out pays the whole of the last month. Hurst's only rule.
     */
    NEXT_FIRST;

    /** The day a lease's rent starts under this rule, for a household that takes the home on possession. */
    public LocalDate firstDay(LocalDate possession) {
        if (possession == null) {
            return null;
        }
        return switch (this) {
            case NEXT_FIRST -> possession.getDayOfMonth() == 1
                    ? possession
                    : possession.withDayOfMonth(1).plusMonths(1);
        };
    }

    /** Whether a lease or a rent step may start on this day under this rule. */
    public boolean allows(LocalDate start) {
        if (start == null) {
            return true;
        }
        return switch (this) {
            case NEXT_FIRST -> start.getDayOfMonth() == 1;
        };
    }

    /**
     * The rule a request names ("NEXT_FIRST"). 400 on the field (terms_template.rent_starts_unknown) for a name
     * that is not one of the rules.
     */
    public static RentStarts fromRequest(Object raw, String field) {
        if (raw instanceof RentStarts rule) {
            return rule;
        }
        String name = raw == null ? "" : raw.toString().trim();
        for (RentStarts rule : values()) {
            if (rule.name().equals(name)) {
                return rule;
            }
        }
        throw InvalidRequest.onField(field, "terms_template.rent_starts_unknown", name);
    }
}

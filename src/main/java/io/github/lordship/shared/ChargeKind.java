package io.github.lordship.shared;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The kinds of charge a deal bills. A deal's "Counts as rent" (rent_kinds) is a list of these, and the state's law
 * ({@link RentLaw}) says which kinds may be in that list.
 *
 * <p>An enum rather than a table, for the reason {@link DocumentToken} gives: billing needs code behind each kind
 * (how the charge is worked out, what a lease calls the charge). Which kinds a deal counts as rent is data.
 *
 * <p>The Postgres enum charge_kind (V1) lists the same values in the same order. Adding a kind is one
 * {@code ALTER TYPE charge_kind ADD VALUE} and one constant here, with the kind's lease words and whether the
 * kind comes due monthly. No new columns.
 *
 * <p>The ledger (TransactionType) and property_fee_cap.fee_type still name charges their own way; they can adopt
 * this list when accounts are reworked.
 */
public enum ChargeKind {
    RENT(true, "rent"),
    WATER(true, "water"),
    SEWER(true, "sewer"),
    POWER(true, "power"),
    TRASH(true, "trash"),
    CAR_FEE(true, "car fees"),
    PET_FEE(true, "pet fees"),
    LATE_FEE(false, "late fees"),
    NSF_FEE(false, "returned check fees"),
    RULE_VIOLATION_FEE(false, "rule violation fees"),
    NOTICE_FEE(false, "notice fees"),
    MISC_CHARGE(false, "other charges");

    private final boolean monthly;
    private final String leaseWords;

    ChargeKind(boolean monthly, String leaseWords) {
        this.monthly = monthly;
        this.leaseWords = leaseWords;
    }

    /** Whether the charge comes due every month (rent, a utility, a car or pet fee) rather than once, for an event. */
    public boolean isMonthly() {
        return monthly;
    }

    /** How a lease names the kind in a list ("lot rent, water and sewer"). Rent on a lot lease is "lot rent". */
    public String leaseWords(AgreementType agreementType) {
        return (this == RENT && agreementType == AgreementType.LAND) ? "lot rent" : leaseWords;
    }

    /** Every kind that comes due monthly, in declaration order. */
    public static List<ChargeKind> monthly() {
        return EnumSet.allOf(ChargeKind.class).stream().filter(ChargeKind::isMonthly).toList();
    }

    /** The kinds in declaration order, each kind once. */
    public static List<ChargeKind> inOrder(Collection<ChargeKind> kinds) {
        return kinds.isEmpty() ? List.of() : List.copyOf(EnumSet.copyOf(kinds));
    }

    /** A Postgres charge_kind[] read as text ("{RENT,WATER}"). Null reads as no kinds. */
    public static List<ChargeKind> fromDatabase(String array) {
        if (array == null) {
            return List.of();
        }
        String inside = array.strip();
        if (inside.startsWith("{") && inside.endsWith("}")) {
            inside = inside.substring(1, inside.length() - 1);
        }
        List<ChargeKind> kinds = new ArrayList<>();
        for (String part : inside.split(",")) {
            String name = part.strip();
            if (name.isEmpty()) {
                continue;
            }
            try {
                kinds.add(valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("No ChargeKind constant for database value '" + name + "'", e);
            }
        }
        return inOrder(kinds);
    }

    /** Kinds as a Postgres array literal ("{RENT,WATER}"), for {@code CAST(:x AS charge_kind[])}. */
    public static String toDatabase(Collection<?> kinds) {
        return kinds.stream()
                .map(kind -> ((ChargeKind) kind).name())
                .collect(Collectors.joining(",", "{", "}"));
    }

    /**
     * The kinds a request sent, as a JSON list of names (["RENT", "WATER"]), in declaration order and each kind
     * once. Refuses (400, on the field) a value that is not a list, and a name that is not a kind.
     */
    public static List<ChargeKind> fromRequest(Object raw, String field) {
        if (raw == null) {
            throw InvalidRequest.onField(field, "value.required");
        }
        if (!(raw instanceof Collection<?> items)) {
            throw InvalidRequest.onField(field, "charge_kind.not_a_list");
        }
        List<ChargeKind> kinds = new ArrayList<>();
        for (Object item : items) {
            String name = Patches.text(item);
            if (name == null) {
                continue;
            }
            try {
                kinds.add(valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw InvalidRequest.onField(field, "value.not_a_choice", name);
            }
        }
        return inOrder(kinds);
    }
}

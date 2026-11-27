package io.github.lordship.tenancy;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * One item of the office's list for a move-in's signed lease (Glade's Office review), in the order the office reads
 * the scan, and REQUEST: a check the office adds by hand, with a question for the park and the park's answer.
 *
 * <p>The list is fixed here for now. A list each property edits for itself (move_in_checklist_item, copied from a
 * global list the way a terms template is) is a later slice; the names here would become that list's first rows.
 */
public enum MoveInCheckItem {
    /** Every tenant signed and dated the lease. A person confirms: a machine never decides a signature. */
    TENANTS_SIGNED,
    /** The manager signed for the park. A person confirms. */
    MANAGER_SIGNED,
    /** Every page of the lease is in the scan, once, in order, and no page is from another lease. */
    PAGES_IN_ORDER,
    /** The Tenant Information Form is filled out and tells the same story as the screening form. */
    TIF,
    /** Assistance animal papers, when the household has an assistance animal; NOT_NEEDED otherwise. */
    ASSISTANCE_ANIMAL,
    /** The first month's rent was received. */
    FIRST_RENT,
    /** The security deposit was received. Only a lease that takes a deposit (a residential deal) asks for one. */
    DEPOSIT,
    /** Nothing more is owed at the signing. */
    NOTHING_OWED,
    /** The payment details are right: an allowed kind of payment, the right amount, a payer on the lease. */
    PAYMENT_DETAILS,
    /** A check the office adds by hand: a question, and the park's answer. */
    REQUEST;

    /** The fixed items, in the order the office reads the scan: every item but REQUEST. */
    public static List<MoveInCheckItem> fixed() {
        return Arrays.stream(values()).filter(item -> item != REQUEST).toList();
    }

    /** Whether the quick page check of a signed scan decides this item by itself (only PAGES_IN_ORDER). */
    public boolean machineDecides() {
        return this == PAGES_IN_ORDER;
    }

    /** Whether this item applies to a move-in: DEPOSIT only to a lease that takes a deposit, every other always. */
    public boolean appliesTo(boolean depositTaken) {
        return this != DEPOSIT || depositTaken;
    }

    /** The fixed item a request names ("TENANTS_SIGNED"), or empty for any other name, REQUEST included. */
    public static Optional<MoveInCheckItem> fixedNamed(String name) {
        return fixed().stream().filter(item -> item.name().equals(name)).findFirst();
    }
}

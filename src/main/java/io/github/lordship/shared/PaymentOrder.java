package io.github.lordship.shared;

/**
 * Which charges a payment covers first, a term of the deal. Billing works the split out from the ledger every
 * time rather than storing the split, so a charge that is voided later moves the payment where the payment belongs.
 *
 * <p>"Rent" here means the deal's rent kinds (Counts as rent), not lot rent alone.
 */
public enum PaymentOrder {
    /**
     * The current month's rent first, then rent past due, then every other charge oldest first. The WA packet
     * (V16), and the only order Washington deals use (RentLaw).
     */
    RENT_FIRST,
    /** Every charge that is not rent oldest first, then past-due rent, then current rent. The MT 2024 packet (V17). */
    FEES_FIRST
}

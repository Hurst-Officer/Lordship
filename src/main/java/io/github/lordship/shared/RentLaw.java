package io.github.lordship.shared;

import java.util.List;
import java.util.Locale;

/**
 * What a state's law says about rent on one kind of agreement: which kinds of charge may count as rent, which
 * payment orders a deal may use, and what a new deal starts with. Where the law is silent on the payment order,
 * the payment orders are company policy, and the entry says so (paymentOrderByPolicy).
 *
 * <p>Code rather than data, like {@link DocumentToken}: a law Lordship knows is a law somebody read. A state and
 * agreement type with no entry here is an unknown law, and an unknown law limits nothing: every kind may count as
 * rent, either order is allowed, and a new deal starts with rent alone, applied first.
 *
 * <p>Where the law decides, the starting values follow the law. Where the law leaves a choice, they follow
 * Hurst's packet for that state: the WA packet (V16) counts lot rent alone, and the MT packet (V17) applies a
 * payment to fees first.
 *
 * <p>Whatever a deal counts as rent, rent itself is always in the list.
 *
 * @param source the statutes Counts as rent rests on, quoted in refusals; null for an unknown law
 * @param mayCountAsRent the most a deal may put in Counts as rent, in declaration order
 * @param startingRentKinds what Counts as rent holds on a new deal
 * @param paymentOrderSource the statutes the payment orders rest on, or are read with when they are company
 *                           policy; quoted in refusals; null for an unknown law
 * @param paymentOrderByPolicy true when the law does not set the order a payment is applied in, and the payment
 *                             orders are company policy read with paymentOrderSource
 * @param paymentOrders the payment orders a deal may use
 * @param startingPaymentOrder the payment order of a new deal
 */
public record RentLaw(
        String state,
        AgreementType agreementType,
        String source,
        List<ChargeKind> mayCountAsRent,
        List<ChargeKind> startingRentKinds,
        String paymentOrderSource,
        boolean paymentOrderByPolicy,
        List<PaymentOrder> paymentOrders,
        PaymentOrder startingPaymentOrder
) {
    public RentLaw {
        mayCountAsRent = ChargeKind.inOrder(mayCountAsRent);
        startingRentKinds = ChargeKind.inOrder(startingRentKinds);
        paymentOrders = List.copyOf(paymentOrders);
    }

    private static final List<RentLaw> KNOWN = List.of(
            // RCW 59.20.030(25): rent is the recurring and periodic charges in the rental agreement, which may
            // include utilities; late fees, damages, deposits, legal costs and other fees are not rent.
            // The MHLTA has no section on the order a payment is applied in, so RENT_FIRST only is company policy,
            // read with the definition: a payment put to fees first leaves rent unpaid, which turns fees the
            // definition keeps out of rent into unpaid rent all the same. The WA packet words rent first only.
            new RentLaw("WA", AgreementType.LAND, "RCW 59.20.030(25)",
                    ChargeKind.monthly(), List.of(ChargeKind.RENT),
                    "RCW 59.20.030(25)", true,
                    List.of(PaymentOrder.RENT_FIRST), PaymentOrder.RENT_FIRST),
            // RCW 59.18.030: the same definition of rent. RCW 59.18.283(1): a payment goes to rent before late
            // payments, damages, legal costs or other fees.
            new RentLaw("WA", AgreementType.RESIDENTIAL, "RCW 59.18.030",
                    ChargeKind.monthly(), List.of(ChargeKind.RENT),
                    "RCW 59.18.283", false,
                    List.of(PaymentOrder.RENT_FIRST), PaymentOrder.RENT_FIRST),
            // MCA 70-33-103(15): rent is every payment to the landlord under the agreement, late fees and other
            // charges included, except the security deposit. This is the definition as the MT 2024 packet reads
            // it; check the current text of the section before relying on the entry. Montana leaves the payment
            // order to the agreement.
            new RentLaw("MT", AgreementType.LAND, "MCA 70-33-103(15)",
                    List.of(ChargeKind.values()), List.of(ChargeKind.values()),
                    "MCA 70-33-103(15)", false,
                    List.of(PaymentOrder.values()), PaymentOrder.FEES_FIRST));

    /** Every law Lordship knows, for Glade's deal editor. */
    public static List<RentLaw> known() {
        return KNOWN;
    }

    /** The law for a state and agreement type, or the unknown law when Lordship knows none. A null state is unknown. */
    public static RentLaw of(String state, AgreementType agreementType) {
        return KNOWN.stream()
                .filter(law -> law.state().equals(state) && law.agreementType() == agreementType)
                .findFirst()
                .orElseGet(() -> new RentLaw(state, agreementType, null,
                        List.of(ChargeKind.values()), List.of(ChargeKind.RENT),
                        null, false,
                        List.of(PaymentOrder.values()), PaymentOrder.RENT_FIRST));
    }

    /** Whether Lordship knows this law, rather than limiting nothing for want of knowing. */
    public boolean isKnown() {
        return source != null;
    }

    /**
     * Refuses (400, on the field at fault) a Counts as rent without rent, a kind this law keeps out of rent, and a
     * payment order this law, or the company policy read with this law, does not allow.
     */
    public void requireAllowed(List<ChargeKind> rentKinds, PaymentOrder paymentOrder) {
        if (!rentKinds.contains(ChargeKind.RENT)) {
            throw InvalidRequest.onField("rent_kinds", "rent_kinds.need_rent");
        }
        if (!mayCountAsRent.containsAll(rentKinds)) {
            throw InvalidRequest.onField("rent_kinds", "rent_kinds.not_allowed_in_state", state, source);
        }
        if (!paymentOrders.contains(paymentOrder)) {
            String code = paymentOrderByPolicy
                    ? "payment_order.not_allowed_by_policy"
                    : "payment_order.not_allowed_in_state";
            throw InvalidRequest.onField("payment_order", code, state, paymentOrderSource);
        }
    }

    /** A state as a request sends one: blank is no state; otherwise two letters, upper-cased ("wa" is WA). */
    public static String readState(Object raw, String field) {
        String text = Patches.text(raw);
        if (text == null) {
            return null;
        }
        String state = text.toUpperCase(Locale.ROOT);
        if (state.length() != 2 || !state.chars().allMatch(c -> c >= 'A' && c <= 'Z')) {
            throw InvalidRequest.onField(field, "value.not_a_state", text);
        }
        return state;
    }

    /** The payment order a request sent, by name. Refuses (400, on the field) none, and a name that is not an order. */
    public static PaymentOrder readPaymentOrder(Object raw, String field) {
        PaymentOrder order = Patches.choice(raw, PaymentOrder.class, field);
        if (order == null) {
            throw InvalidRequest.onField(field, "value.required");
        }
        return order;
    }
}

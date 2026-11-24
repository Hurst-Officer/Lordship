package io.github.lordship.shared;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RentLawTest {

    // ---- Washington lots -----------------------------------------------------

    @Test
    void washingtonLand_shouldKeepOneTimeFeesOutOfRent() {
        // Arrange -- RCW 59.20.030(25): late fees, damages and other fees are not rent
        RentLaw law = RentLaw.of("WA", AgreementType.LAND);

        // Act
        InvalidRequest refused = assertThrows(InvalidRequest.class,
                () -> law.requireAllowed(List.of(ChargeKind.RENT, ChargeKind.LATE_FEE), PaymentOrder.RENT_FIRST));

        // Assert
        assertEquals("rent_kinds.not_allowed_in_state", refused.problem().code());
        assertEquals("rent_kinds", refused.problem().field());
    }

    @Test
    void washingtonLand_shouldLetMonthlyChargesCountAsRent_andApplyPaymentsToRentFirstOnly() {
        RentLaw law = RentLaw.of("WA", AgreementType.LAND);

        // Assert -- utilities and the car and pet fees are recurring charges, so the office may count them
        assertDoesNotThrow(() -> law.requireAllowed(
                List.of(ChargeKind.RENT, ChargeKind.WATER, ChargeKind.PET_FEE), PaymentOrder.RENT_FIRST));
        InvalidRequest refused = assertThrows(InvalidRequest.class,
                () -> law.requireAllowed(List.of(ChargeKind.RENT), PaymentOrder.FEES_FIRST));
        // the MHLTA does not set the order, so rent first is company policy, and the refusal says so
        assertEquals("payment_order.not_allowed_by_policy", refused.problem().code());

        // A new Washington deal counts lot rent alone, as the WA packet does
        assertEquals(List.of(ChargeKind.RENT), law.startingRentKinds());
        assertEquals(PaymentOrder.RENT_FIRST, law.startingPaymentOrder());
    }

    // ---- Montana lots and unknown laws ----------------------------------------

    @Test
    void montanaLand_shouldCountEveryCharge_andStartWithFeesFirst() {
        // Arrange -- MCA 70-33-103(15): every payment but the deposit is rent
        RentLaw law = RentLaw.of("MT", AgreementType.LAND);

        // Assert
        assertDoesNotThrow(() -> law.requireAllowed(List.of(ChargeKind.values()), PaymentOrder.FEES_FIRST));
        assertEquals(List.of(ChargeKind.values()), law.startingRentKinds());
        assertEquals(PaymentOrder.FEES_FIRST, law.startingPaymentOrder());
    }

    @Test
    void anUnknownLaw_shouldLimitNothing_butRentStillCountsAsRent() {
        // Arrange -- no law is written down for Oregon yet
        RentLaw law = RentLaw.of("OR", AgreementType.LAND);

        // Assert
        assertFalse(law.isKnown());
        assertDoesNotThrow(() -> law.requireAllowed(
                List.of(ChargeKind.RENT, ChargeKind.LATE_FEE), PaymentOrder.FEES_FIRST));
        InvalidRequest refused = assertThrows(InvalidRequest.class,
                () -> law.requireAllowed(List.of(ChargeKind.WATER), PaymentOrder.RENT_FIRST));
        assertEquals("rent_kinds.need_rent", refused.problem().code());
    }

    // ---- reading a request ----------------------------------------------------

    @Test
    void readState_shouldUpperCaseTwoLetters_andTreatBlankAsNoState() {
        assertEquals("WA", RentLaw.readState(" wa ", "state"));
        assertNull(RentLaw.readState("", "state"));

        InvalidRequest refused = assertThrows(InvalidRequest.class, () -> RentLaw.readState("Wash", "state"));
        assertEquals("value.not_a_state", refused.problem().code());
    }
}

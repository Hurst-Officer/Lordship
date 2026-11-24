package io.github.lordship.termstemplate.internal;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.ChargeKind;
import io.github.lordship.shared.FeeMethod;
import io.github.lordship.shared.LateFeeTier;
import io.github.lordship.shared.PaymentOrder;
import io.github.lordship.shared.SecurityDepositMethod;
import io.github.lordship.termstemplate.TermsTemplate;
import io.github.lordship.shared.UtilityMethod;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// Component order matches the column order in V1__enums_and_property.sql.
public record TermsTemplateRow(
        UUID uuid,
        UUID property,
        UUID copiedFrom, // provenance only; may point at a retired template
        String name,

        AgreementType agreementType,
        BigDecimal targetRate, // where existing tenancies are steered
        BigDecimal askingRate, // what a new applicant is quoted

        BigDecimal escalationPercent, // null means a single rate for the whole term
        Integer escalationMonths,     // how often it applies; 12 is annual

        BigDecimal carFee,
        Integer allowedCars, // cars allowed before being charged fees
        Integer carsMax, // max number of cars permissible (even with fees)

        BigDecimal petFee,
        Integer allowedPets,

        Integer paymentDueDay,
        Integer gracePeriodDays,

        FeeMethod ruleViolationFeeMethod,
        BigDecimal ruleViolationFeeAmount,

        FeeMethod nsfFeeMethod,
        BigDecimal nsfFeeAmount,

        FeeMethod lateFeeMethod,
        BigDecimal lateFeeAmount,
        List<LateFeeTier> lateFeeTiers, // the chart for TIERED; empty for every other method

        UtilityMethod waterMethod,
        BigDecimal waterFlatAmount,

        UtilityMethod powerMethod,
        BigDecimal powerFlatAmount,

        UtilityMethod sewerMethod,
        BigDecimal sewerFlatAmount,

        UtilityMethod trashMethod,
        BigDecimal trashFlatAmount,

        SecurityDepositMethod securityDepositMethod,
        BigDecimal securityDepositAmount,

        PaymentOrder paymentOrder,  // which charges a payment covers first
        List<ChargeKind> rentKinds, // "Counts as rent"; always holds RENT
        String state,               // whose law the row answers to; set when the row is made, never changed

        String note,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        UUID createdBy, // original author; never changes
        OffsetDateTime deletedAt
) {
    public TermsTemplate toTermsTemplate() {
        return new TermsTemplate(
                uuid, property, copiedFrom, name, agreementType, targetRate, askingRate,
                escalationPercent, escalationMonths,
                carFee, allowedCars, carsMax, petFee, allowedPets,
                paymentDueDay, gracePeriodDays,
                ruleViolationFeeMethod, ruleViolationFeeAmount,
                nsfFeeMethod, nsfFeeAmount,
                lateFeeMethod, lateFeeAmount, lateFeeTiers,
                waterMethod, waterFlatAmount,
                powerMethod, powerFlatAmount,
                sewerMethod, sewerFlatAmount,
                trashMethod, trashFlatAmount,
                securityDepositMethod, securityDepositAmount,
                paymentOrder, rentKinds, state,
                note, createdAt, updatedAt, createdBy, deletedAt
        );
    }

    // Minimal insert -- every other term column has a DB default.
    // The payment order and Counts as rent are the column defaults (rent alone, applied first); the service sets
    // what the state's law starts a template with (withRentRules).
    // Nulls are grouped and labelled so a miscount is visible; positional
    // mistakes here compile silently because every component is a reference type.
    public TermsTemplateRow(UUID property, String name, AgreementType agreementType, String state, UUID createdBy) {
        this(null, property, null, name, agreementType,
                null, null,                    // targetRate, askingRate
                null, null,                    // escalationPercent, escalationMonths
                null, null, null,              // carFee, allowedCars, carsMax
                null, null,                    // petFee, allowedPets
                null, null,                    // paymentDueDay, gracePeriodDays
                null, null,                    // ruleViolationFee method, amount
                null, null,                    // nsfFee method, amount
                null, null, null,              // lateFee method, amount, tiers
                null, null,                    // water method, amount
                null, null,                    // power method, amount
                null, null,                    // sewer method, amount
                null, null,                    // trash method, amount
                null, null,         // security deposit method, amount
                PaymentOrder.RENT_FIRST, List.of(ChargeKind.RENT), state, // payment order, rent kinds, state
                null,                          // note
                null, null,                    // createdAt, updatedAt
                createdBy,
                null);                         // deletedAt
    }

    // Copies a global template into a property, keeping the terms and dropping
    // identity. copiedFrom records the source; the copying agent is the author
    // of the copy, not whoever authored the template.
    // The copy keeps the template's state, payment order and Counts as rent: a template goes only to a
    // property in the template's state (the service checks).
    public TermsTemplateRow copyTo(UUID targetProperty, UUID copiedBy) {
        return new TermsTemplateRow(
                null, targetProperty, uuid, name, agreementType, targetRate, askingRate,
                escalationPercent, escalationMonths,
                carFee, allowedCars, carsMax, petFee, allowedPets,
                paymentDueDay, gracePeriodDays,
                ruleViolationFeeMethod, ruleViolationFeeAmount,
                nsfFeeMethod, nsfFeeAmount,
                lateFeeMethod, lateFeeAmount, lateFeeTiers,
                waterMethod, waterFlatAmount,
                powerMethod, powerFlatAmount,
                sewerMethod, sewerFlatAmount,
                trashMethod, trashFlatAmount,
                securityDepositMethod, securityDepositAmount,
                paymentOrder, rentKinds, state,
                note, null, null, copiedBy, null
        );
    }

    // Starts a new global template from this template's terms, or from a property's deal: the terms are kept,
    // identity is dropped, and the new template belongs to no property. The new template takes the name and
    // the agreement type given. The copying agent is the author of the copy.
    // copiedFrom stays null, as on every global template: copiedFrom names the global template a property's
    // deal was copied from, and nothing else.
    // The two rates are kept only when this row is a global template. A property's rates belong to that
    // property's market, and a template hands its rates to every property that copies the template.
    // The payment order and Counts as rent are kept, with no state: the service decides the new template's state
    // and whether the two still apply under that state's law (withRentRules).
    public TermsTemplateRow copyAsGlobal(String newName, AgreementType newAgreementType, UUID copiedBy) {
        boolean fromGlobal = property == null;
        return new TermsTemplateRow(
                null, null, null, newName, newAgreementType,
                fromGlobal ? targetRate : null, fromGlobal ? askingRate : null,
                escalationPercent, escalationMonths,
                carFee, allowedCars, carsMax, petFee, allowedPets,
                paymentDueDay, gracePeriodDays,
                ruleViolationFeeMethod, ruleViolationFeeAmount,
                nsfFeeMethod, nsfFeeAmount,
                lateFeeMethod, lateFeeAmount, lateFeeTiers,
                waterMethod, waterFlatAmount,
                powerMethod, powerFlatAmount,
                sewerMethod, sewerFlatAmount,
                trashMethod, trashFlatAmount,
                securityDepositMethod, securityDepositAmount,
                paymentOrder, rentKinds, null,
                note, null, null, copiedBy, null
        );
    }

    // The same row with another state, Counts as rent and payment order, before the row is saved.
    public TermsTemplateRow withRentRules(String newState, List<ChargeKind> newRentKinds, PaymentOrder newPaymentOrder) {
        return new TermsTemplateRow(
                uuid, property, copiedFrom, name, agreementType, targetRate, askingRate,
                escalationPercent, escalationMonths,
                carFee, allowedCars, carsMax, petFee, allowedPets,
                paymentDueDay, gracePeriodDays,
                ruleViolationFeeMethod, ruleViolationFeeAmount,
                nsfFeeMethod, nsfFeeAmount,
                lateFeeMethod, lateFeeAmount, lateFeeTiers,
                waterMethod, waterFlatAmount,
                powerMethod, powerFlatAmount,
                sewerMethod, sewerFlatAmount,
                trashMethod, trashFlatAmount,
                securityDepositMethod, securityDepositAmount,
                newPaymentOrder, newRentKinds, newState,
                note, createdAt, updatedAt, createdBy, deletedAt
        );
    }
}
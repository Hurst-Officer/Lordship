package io.github.lordship.termstemplate.internal;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.FeeMethod;
import io.github.lordship.shared.SecurityDepositMethod;
import io.github.lordship.termstemplate.TermsTemplate;
import io.github.lordship.shared.UtilityMethod;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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

        String note,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        UUID createdBy, // original author; never changes
        OffsetDateTime deletedAt
) {
    public TermsTemplate toTermsTemplate() {
        return new TermsTemplate(
                uuid, property, copiedFrom, name, agreementType, targetRate, askingRate,
                carFee, allowedCars, carsMax, petFee, allowedPets,
                paymentDueDay, gracePeriodDays,
                ruleViolationFeeMethod, ruleViolationFeeAmount,
                nsfFeeMethod, nsfFeeAmount,
                lateFeeMethod, lateFeeAmount,
                waterMethod, waterFlatAmount,
                powerMethod, powerFlatAmount,
                sewerMethod, sewerFlatAmount,
                trashMethod, trashFlatAmount,
                securityDepositMethod, securityDepositAmount,
                note, createdAt, updatedAt, createdBy, deletedAt
        );
    }

    // Minimal insert -- every other column has a DB default.
    // Nulls are grouped and labelled so a miscount is visible; positional
    // mistakes here compile silently because every component is a reference type.
    public TermsTemplateRow(UUID property, String name, AgreementType agreementType, UUID createdBy) {
        this(null, property, null, name, agreementType,
                null, null,                    // targetRate, askingRate
                null, null, null,              // carFee, allowedCars, carsMax
                null, null,                    // petFee, allowedPets
                null, null,                    // paymentDueDay, gracePeriodDays
                null, null,                    // ruleViolationFee method, amount
                null, null,                    // nsfFee method, amount
                null, null,                    // lateFee method, amount
                null, null,                    // water method, amount
                null, null,                    // power method, amount
                null, null,                    // sewer method, amount
                null, null,                    // trash method, amount
                null, null,         // security deposit method, amount
                null,                          // note
                null, null,                    // createdAt, updatedAt
                createdBy,
                null);                         // deletedAt
    }

    // Copies a global template into a property, keeping the terms and dropping
    // identity. copiedFrom records the source; the copying agent is the author
    // of the copy, not whoever authored the template.
    public TermsTemplateRow copyTo(UUID targetProperty, UUID copiedBy) {
        return new TermsTemplateRow(
                null, targetProperty, uuid, name, agreementType, targetRate, askingRate,
                carFee, allowedCars, carsMax, petFee, allowedPets,
                paymentDueDay, gracePeriodDays,
                ruleViolationFeeMethod, ruleViolationFeeAmount,
                nsfFeeMethod, nsfFeeAmount,
                lateFeeMethod, lateFeeAmount,
                waterMethod, waterFlatAmount,
                powerMethod, powerFlatAmount,
                sewerMethod, sewerFlatAmount,
                trashMethod, trashFlatAmount,
                securityDepositMethod, securityDepositAmount,
                note, null, null, copiedBy, null
        );
    }
}
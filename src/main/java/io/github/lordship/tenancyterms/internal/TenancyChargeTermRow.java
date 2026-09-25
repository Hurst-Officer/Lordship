package io.github.lordship.tenancyterms.internal;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.FeeMethod;
import io.github.lordship.shared.SecurityDepositMethod;
import io.github.lordship.shared.UtilityMethod;
import io.github.lordship.termstemplate.TermsTemplate;
import io.github.lordship.tenancyterms.TenancyChargeTerm;
import io.github.lordship.tenancyterms.TenancyTermSource;
import io.github.lordship.tenancyterms.TenancyTermStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

// Component order matches the column order in V9__documents_and_deals.sql.
public record TenancyChargeTermRow(
        UUID uuid,
        UUID tenancy,
        LocalDate validAt,

        AgreementType agreementType, // not patchable; set once at creation

        BigDecimal rate, // COALESCE(the lot's target_rate for this type, terms_template.target_rate)

        BigDecimal carFee,
        Integer allowedCars, // cars allowed before being charged fees
        Integer carsMax, // max number of cars permissible (even with fees)

        BigDecimal petFee,
        Integer allowedPets,

        Integer paymentDueDay,
        Integer gracePeriodDays,

        FeeMethod ruleViolationFeeMethod, // NONE, FLAT
        BigDecimal ruleViolationFeeAmount,

        FeeMethod nsfFeeMethod, // NONE, FLAT, BANK_OR_FLAT
        BigDecimal nsfFeeAmount,

        FeeMethod lateFeeMethod, // NONE, FLAT, PERCENT_OF_RENT
        BigDecimal lateFeeAmount, // a percent OR a flat rate, per the method

        UtilityMethod waterMethod,
        BigDecimal waterFlatAmount,

        UtilityMethod powerMethod,
        BigDecimal powerFlatAmount,

        UtilityMethod sewerMethod,
        BigDecimal sewerFlatAmount,

        UtilityMethod trashMethod, // NONE, FLAT, RUBS -- no SUBMETERED
        BigDecimal trashFlatAmount,

        SecurityDepositMethod securityDepositMethod,
        BigDecimal securityDepositAmount,

        TenancyTermStatus status,
        TenancyTermSource source,
        UUID sourceUuid,    // the document this term was written for; null for MIGRATION and CORRECTION
        UUID termsTemplate, // which template seeded the values
        String correctionReason, // why an admin entered this term; required for CORRECTION

        OffsetDateTime cancelledAt,
        UUID cancelledBy,
        String cancelReason,
        OffsetDateTime deletedAt, // only for terms that never generated charges

        String note,
        OffsetDateTime createdAt,
        UUID createdBy
) {

    public TenancyChargeTerm toTenancyChargeTerm() {
        return new TenancyChargeTerm(
                uuid, tenancy, validAt, agreementType,
                rate, carFee, allowedCars, carsMax, petFee, allowedPets,
                paymentDueDay, gracePeriodDays,
                ruleViolationFeeMethod, ruleViolationFeeAmount,
                nsfFeeMethod, nsfFeeAmount,
                lateFeeMethod, lateFeeAmount,
                waterMethod, waterFlatAmount,
                powerMethod, powerFlatAmount,
                sewerMethod, sewerFlatAmount,
                trashMethod, trashFlatAmount,
                securityDepositMethod, securityDepositAmount,
                status, source, sourceUuid, termsTemplate, correctionReason,
                cancelledAt, cancelledBy, cancelReason, deletedAt,
                note, createdAt, createdBy
        );
    }

    // A new PROPOSED term with every setting copied from the property's template.
    // The caller picks the rate (the lot's rate wins over the template's).
    // sourceUuid is the document the term is for, or null for MIGRATION and CORRECTION.
    //
    // The nulls are labelled because a wrong position here still compiles.
    public static TenancyChargeTermRow fromTemplate(
            UUID tenancy,
            TermsTemplate template,
            BigDecimal rate,
            LocalDate validAt,
            TenancyTermSource source,
            UUID sourceUuid,
            UUID createdBy) {

        return new TenancyChargeTermRow(
                null,                          // uuid
                tenancy,
                validAt,
                template.agreementType(),
                rate,
                template.carFee(),
                template.allowedCars(),
                template.carsMax(),
                template.petFee(),
                template.allowedPets(),
                template.paymentDueDay(),
                template.gracePeriodDays(),
                template.ruleViolationFeeMethod(),
                template.ruleViolationFeeAmount(),
                template.nsfFeeMethod(),
                template.nsfFeeAmount(),
                template.lateFeeMethod(),
                template.lateFeeAmount(),
                template.waterMethod(),
                template.waterFlatAmount(),
                template.powerMethod(),
                template.powerFlatAmount(),
                template.sewerMethod(),
                template.sewerFlatAmount(),
                template.trashMethod(),
                template.trashFlatAmount(),
                template.securityDepositMethod(),
                template.securityDepositAmount(),
                TenancyTermStatus.PROPOSED,
                source,
                sourceUuid,
                template.uuid(),               // termsTemplate
                null,                          // correctionReason
                null, null, null,              // cancelledAt, cancelledBy, cancelReason
                null,                          // deletedAt
                null,                          // note
                null,                          // createdAt
                createdBy
        );
    }

    // A new PROPOSED step that keeps every setting of an existing term.
    // Only the date and the rent change. Used when a document's schedule is rebuilt,
    // so fees the office worker already edited are not lost.
    public static TenancyChargeTermRow copyForStep(
            TenancyChargeTermRow from,
            BigDecimal rate,
            LocalDate validAt,
            UUID createdBy) {

        return new TenancyChargeTermRow(
                null,                          // uuid
                from.tenancy(),
                validAt,
                from.agreementType(),
                rate,
                from.carFee(),
                from.allowedCars(),
                from.carsMax(),
                from.petFee(),
                from.allowedPets(),
                from.paymentDueDay(),
                from.gracePeriodDays(),
                from.ruleViolationFeeMethod(),
                from.ruleViolationFeeAmount(),
                from.nsfFeeMethod(),
                from.nsfFeeAmount(),
                from.lateFeeMethod(),
                from.lateFeeAmount(),
                from.waterMethod(),
                from.waterFlatAmount(),
                from.powerMethod(),
                from.powerFlatAmount(),
                from.sewerMethod(),
                from.sewerFlatAmount(),
                from.trashMethod(),
                from.trashFlatAmount(),
                from.securityDepositMethod(),
                from.securityDepositAmount(),
                TenancyTermStatus.PROPOSED,
                from.source(),
                from.sourceUuid(),
                from.termsTemplate(),
                from.correctionReason(),
                null, null, null,              // cancelledAt, cancelledBy, cancelReason
                null,                          // deletedAt
                null,                          // note
                null,                          // createdAt
                createdBy
        );
    }

    public TenancyChargeTermRow withCorrectionReason(String reason) {
        return new TenancyChargeTermRow(
                uuid, tenancy, validAt, agreementType,
                rate, carFee, allowedCars, carsMax, petFee, allowedPets,
                paymentDueDay, gracePeriodDays,
                ruleViolationFeeMethod, ruleViolationFeeAmount,
                nsfFeeMethod, nsfFeeAmount,
                lateFeeMethod, lateFeeAmount,
                waterMethod, waterFlatAmount,
                powerMethod, powerFlatAmount,
                sewerMethod, sewerFlatAmount,
                trashMethod, trashFlatAmount,
                securityDepositMethod, securityDepositAmount,
                status, source, sourceUuid, termsTemplate, reason,
                cancelledAt, cancelledBy, cancelReason, deletedAt,
                note, createdAt, createdBy);
    }
}
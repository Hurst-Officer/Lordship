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
        UUID sourceUuid,    // the instrument that produced this deal
        UUID termsTemplate, // which template seeded the values
        UUID batch,         // groups one bulk run so it can be reviewed or abandoned together

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
                status, source, sourceUuid, termsTemplate, batch,
                cancelledAt, cancelledBy, cancelReason, deletedAt,
                note, createdAt, createdBy
        );
    }

    // A new term seeded from the property's template for this agreement type.
    // The rate is resolved by the caller, since it prefers the lot's target_rate
    // over the template's. Always lands in PROPOSED with no instrument attached:
    // the CHECK constraints are escaped while status = 'PROPOSED', so a term
    // copied from a template with gaps in it is still insertable.
    //
    // Nulls are grouped and labelled so a miscount is visible; positional
    // mistakes here compile silently because every component is a reference type.
    public static TenancyChargeTermRow fromTemplate(
            UUID tenancy,
            TermsTemplate template,
            BigDecimal rate,
            LocalDate validAt,
            TenancyTermSource source,
            UUID batch,
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
                null,                          // sourceUuid - no instrument until one is generated
                template.uuid(),               // termsTemplate
                batch,
                null, null, null,              // cancelledAt, cancelledBy, cancelReason
                null,                          // deletedAt
                null,                          // note
                null,                          // createdAt
                createdBy
        );
    }

    /** A single term created on its own, outside any bulk run. */
    public static TenancyChargeTermRow fromTemplate(
            UUID tenancy,
            TermsTemplate template,
            BigDecimal rate,
            LocalDate validAt,
            TenancyTermSource source,
            UUID createdBy) {
        return fromTemplate(tenancy, template, rate, validAt, source, null, createdBy);
    }
}
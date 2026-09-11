package io.github.lordship.termstemplate.internal;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.FeeMethod;
import io.github.lordship.shared.SecurityDepositMethod;
import io.github.lordship.shared.UtilityMethod;
import io.github.lordship.termstemplate.TermsTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;


public record TermsTemplateResponse(
        UUID uuid,
        UUID property, // NOTE: A null property means this is a global template.
        String name,
        AgreementType agreementType,
        BigDecimal targetRate, // where existing tenancies are steered
        BigDecimal askingRate, // what a new applicant is quoted

        BigDecimal carFee,
        int allowedCars,
        int carsMax,

        BigDecimal petFee,
        int allowedPets,

        int paymentDueDay,
        int gracePeriodDays,

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
        UUID createdBy,
        OffsetDateTime updatedAt
) {
    public static TermsTemplateResponse from(TermsTemplate terms) {
        return new TermsTemplateResponse(
                terms.uuid(), terms.property(), terms.name(), terms.agreementType(),
                terms.targetRate(), terms.askingRate(),
                terms.carFee(), terms.allowedCars(), terms.carsMax(), terms.petFee(), terms.allowedPets(),
                terms.paymentDueDay(), terms.gracePeriodDays(),
                terms.ruleViolationFeeMethod(), terms.ruleViolationFeeAmount(),
                terms.nsfFeeMethod(), terms.nsfFeeAmount(),
                terms.lateFeeMethod(), terms.lateFeeAmount(),
                terms.waterMethod(), terms.waterFlatAmount(),
                terms.powerMethod(), terms.powerFlatAmount(),
                terms.sewerMethod(), terms.sewerFlatAmount(),
                terms.trashMethod(), terms.trashFlatAmount(),
                terms.securityDepositMethod(), terms.securityDepositAmount(),
                terms.note(), terms.createdAt(), terms.createdBy(), terms.updatedAt()
        );
    }
}
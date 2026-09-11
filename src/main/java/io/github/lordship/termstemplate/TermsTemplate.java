package io.github.lordship.termstemplate;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.FeeMethod;
import io.github.lordship.shared.SecurityDepositMethod;
import io.github.lordship.shared.UtilityMethod;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// One property's standard deal for one agreement type: the terms a new tenancy is created from.
public record TermsTemplate(
        UUID uuid,
        UUID property,
        UUID copiedFrom, // the global template this was copied from; null on a global template
        String name,
        AgreementType agreementType,
        BigDecimal targetRate, // where existing tenancies are steered
        BigDecimal askingRate, // what a new applicant is quoted

        BigDecimal carFee,
        int allowedCars, // cars allowed before being charged fees
        int carsMax, // max number of cars permissible (even with fees)

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
        OffsetDateTime updatedAt,
        UUID createdBy,
        OffsetDateTime deletedAt
) {
    public boolean isGlobalTemplate() {
        return property == null;
    }

    public boolean isSoftDeleted() {
        return deletedAt != null;
    }
}
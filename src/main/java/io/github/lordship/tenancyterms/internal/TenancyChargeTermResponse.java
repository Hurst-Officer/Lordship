package io.github.lordship.tenancyterms.internal;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.FeeMethod;
import io.github.lordship.shared.SecurityDepositMethod;
import io.github.lordship.shared.UtilityMethod;
import io.github.lordship.tenancyterms.TenancyChargeTerm;
import io.github.lordship.tenancyterms.TenancyTermSource;
import io.github.lordship.tenancyterms.TenancyTermStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

// What the API returns. deletedAt is deliberately absent: a soft-deleted term
// is never fetched, and carrying it would put the domain record's isSoftDeleted()
// accessor on the wire as "softDeleted". Every field here is one we chose.
public record TenancyChargeTermResponse(
        UUID uuid,
        UUID tenancy,
        LocalDate validAt,

        AgreementType agreementType,

        BigDecimal rate,
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

        TenancyTermStatus status,
        // Chosen, not leaked: the form needs to know whether to render read-only.
        boolean editable,

        TenancyTermSource source,
        UUID sourceUuid,
        UUID termsTemplate,
        UUID batch,

        OffsetDateTime cancelledAt,
        UUID cancelledBy,
        String cancelReason,

        String note,
        OffsetDateTime createdAt,
        UUID createdBy
) {
    public static TenancyChargeTermResponse from(TenancyChargeTerm term) {
        return new TenancyChargeTermResponse(
                term.uuid(), term.tenancy(), term.validAt(), term.agreementType(),
                term.rate(), term.carFee(), term.allowedCars(), term.carsMax(),
                term.petFee(), term.allowedPets(),
                term.paymentDueDay(), term.gracePeriodDays(),
                term.ruleViolationFeeMethod(), term.ruleViolationFeeAmount(),
                term.nsfFeeMethod(), term.nsfFeeAmount(),
                term.lateFeeMethod(), term.lateFeeAmount(),
                term.waterMethod(), term.waterFlatAmount(),
                term.powerMethod(), term.powerFlatAmount(),
                term.sewerMethod(), term.sewerFlatAmount(),
                term.trashMethod(), term.trashFlatAmount(),
                term.securityDepositMethod(), term.securityDepositAmount(),
                term.status(), term.isEditable(),
                term.source(), term.sourceUuid(), term.termsTemplate(), term.batch(),
                term.cancelledAt(), term.cancelledBy(), term.cancelReason(),
                term.note(), term.createdAt(), term.createdBy()
        );
    }
}

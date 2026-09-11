package io.github.lordship.tenancyterms;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.FeeMethod;
import io.github.lordship.shared.SecurityDepositMethod;
import io.github.lordship.shared.UtilityMethod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;


public record TenancyChargeTerm(
        UUID uuid,
        UUID tenancy,
        LocalDate validAt,

        AgreementType agreementType, // set once at creation; never patched

        BigDecimal rate,
        BigDecimal carFee,
        int allowedCars, // cars allowed before being charged fees
        int carsMax, // max number of cars permissible (even with fees)
        BigDecimal petFee,
        int allowedPets,

        int paymentDueDay, // 1-28, so the date lands in every month
        int gracePeriodDays,

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
        UUID batch,         // groups one bulk run

        OffsetDateTime cancelledAt,
        UUID cancelledBy,
        String cancelReason,
        OffsetDateTime deletedAt,

        String note,
        OffsetDateTime createdAt,
        UUID createdBy
) {

    public boolean isEditable() {
        return status.isEditable();
    }

    public boolean isDeletable() {
        return status.isDeletable();
    }

    public boolean isSoftDeleted() {
        return deletedAt != null;
    }

    public boolean isCancelled() {
        return status == TenancyTermStatus.CANCELLED;
    }

    public boolean isInForce() {
        return status == TenancyTermStatus.ACTIVE;
    }

    public LocalDate dueOn(LocalDate periodStart) {
        return periodStart.withDayOfMonth(paymentDueDay);
    }

    public LocalDate lateAfterDate(LocalDate periodStart) {
        return dueOn(periodStart).plusDays(gracePeriodDays);
    }
}
package io.github.lordship.lots.internal;

import io.github.lordship.lots.Lot;
import io.github.lordship.lots.PermissibleAgreementType;
import io.github.lordship.lots.ShapeData;
import io.github.lordship.shared.AgreementType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record LotResponse(
        UUID id,
        UUID propertyId,
        String lotNumber,
        String lotAddress,
        String lotParcel,
        Boolean isRentable,
        String notRentableReason,
        String description,
        String notes,
        Integer sortOrder,
        ShapeData shapeData,
        List<AgreementTypeResponse> permissibleAgreementTypes,
        OffsetDateTime createdAt
) {

    public record AgreementTypeResponse(
            AgreementType agreementType,
            BigDecimal targetRate, // where existing tenancies are steered
            BigDecimal askingRate  // what a new applicant is quoted
    ) {
        public static AgreementTypeResponse from(PermissibleAgreementType type) {
            return new AgreementTypeResponse(
                    type.agreementType(), type.targetRate(), type.askingRate());
        }
    }

    public static LotResponse from(Lot lot) {
        return new LotResponse(
                lot.uuid(),
                lot.propertyId(),
                lot.lotNumber(),
                lot.lotAddress(),
                lot.lotParcel(),
                lot.isRentable(),
                lot.notRentableReason(),
                lot.description(),
                lot.notes(),
                lot.sortOrder(),
                lot.shapeData(),
                lot.permissibleAgreementTypes().stream()
                        .map(AgreementTypeResponse::from)
                        .toList(),
                lot.createdAt()
        );
    }
}
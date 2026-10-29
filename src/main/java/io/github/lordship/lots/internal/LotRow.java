package io.github.lordship.lots.internal;

import io.github.lordship.lots.Lot;
import io.github.lordship.lots.PermissibleAgreementType;
import io.github.lordship.lots.ShapeData;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record LotRow(
        UUID uuid,
        UUID propertyId,
        Boolean isRentable,
        String notRentableReason,
        String lotNumber,
        String titleLink,
        String lotAddress,
        String lotParcel,
        String description,
        String notes,
        Integer sortOrder,
        ShapeData shapeData,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {
    public LotRow {
        if (isRentable != null) {
            boolean hasReason = notRentableReason != null && !notRentableReason.isBlank();
            if (!isRentable && !hasReason) {
                throw new IllegalArgumentException("notRentableReason is required when a lot is not rentable");
            }
            if (isRentable && notRentableReason != null) {
                throw new IllegalArgumentException("notRentableReason must be null when a lot is rentable");
            }
        }
    }

    public Lot toLot(List<LotPermissibleAgreementTypeRow> agreementTypeRows) {
        return new Lot(
                this.uuid,
                this.propertyId,
                this.isRentable,
                this.notRentableReason,
                this.lotNumber,
                this.titleLink,
                this.lotAddress,
                this.lotParcel,
                this.description,
                this.notes,
                this.sortOrder,
                this.shapeData,
                this.createdAt,
                this.deletedAt,
                agreementTypeRows.stream()
                        .map(r -> new PermissibleAgreementType(
                                r.agreementType(), r.targetRate(), r.askingRate()))
                        .toList()
        );
    }

    // Convenience constructor for insert
    public LotRow(UUID propertyId, String lotNumber) {
        this(null, propertyId, true, null, lotNumber, null, null, null, null, null, null, null, null, null);
    }
}
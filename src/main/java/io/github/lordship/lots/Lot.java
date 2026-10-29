package io.github.lordship.lots;

import io.github.lordship.shared.AgreementType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record Lot(
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
        OffsetDateTime deletedAt,
        List<PermissibleAgreementType> permissibleAgreementTypes
) {
  public Lot { // note: this compact constructor
    permissibleAgreementTypes = List.copyOf(permissibleAgreementTypes);
  }

  public boolean isSoftDeleted() {
    return deletedAt != null;
  }

  public boolean permits(AgreementType agreementType) {
    return permissible(agreementType).isPresent();
  }

  /** Where existing tenancies on this lot are being steered. */
  public Optional<BigDecimal> targetRateFor(AgreementType agreementType) {
    return permissible(agreementType).map(PermissibleAgreementType::targetRate);
  }

  /** What a new applicant is quoted for this lot. */
  public Optional<BigDecimal> askingRateFor(AgreementType agreementType) {
    return permissible(agreementType).map(PermissibleAgreementType::askingRate);
  }

  // findFirst before map, never after: target_rate and asking_rate are both
  // nullable, and Stream.findFirst throws on a null element where Optional.map
  // simply returns empty.
  private Optional<PermissibleAgreementType> permissible(AgreementType agreementType) {
    return permissibleAgreementTypes.stream()
            .filter(pat -> pat.agreementType() == agreementType)
            .findFirst();
  }
}
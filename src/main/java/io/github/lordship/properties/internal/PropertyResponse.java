package io.github.lordship.properties.internal;

import io.github.lordship.properties.Property;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record PropertyResponse(
        UUID id,
        String propertyCode,
        String propertyName,
        String propertyStreet,
        String propertyCity,
        String propertyState,
        String propertyZip,
        LocalDate purchaseDate,
        String propertyZoning,
        String propertyParcel,
        String payableTo,
        String remittanceAddress,
        Integer yearBuilt,
        Map<String, Object> customFields,
        UUID propertyManagerId,
        OffsetDateTime createdAt
) {

    public static PropertyResponse from(Property property) {
        return new PropertyResponse(
                property.uuid(),
                property.propertyCode(),
                property.propertyName(),
                property.propertyStreet(),
                property.propertyCity(),
                property.propertyState(),
                property.propertyZip(),
                property.purchaseDate(),
                property.propertyZoning(),
                property.propertyParcel(),
                property.payableTo(),
                property.remittanceAddress(),
                property.yearBuilt(),
                property.customFields(),
                property.propertyManager(),
                property.createdAt()
        );
    }

}

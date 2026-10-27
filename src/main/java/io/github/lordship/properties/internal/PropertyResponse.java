package io.github.lordship.properties.internal;

import io.github.lordship.properties.BusinessNumber;
import io.github.lordship.properties.Property;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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
        String propertyCounty,
        Double latitude,
        Double longitude,
        LocalDate purchaseDate,
        String propertyZoning,
        List<String> propertyParcels,
        BigDecimal acres,
        String payableTo,
        List<BusinessNumber> businessNumbers,
        String remittanceAddress,
        Integer yearBuilt,
        Map<String, Object> customFields,
        Map<String, String> fieldLinks,
        Map<String, String> fieldNotes,
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
                property.propertyCounty(),
                property.latitude(),
                property.longitude(),
                property.purchaseDate(),
                property.propertyZoning(),
                property.propertyParcels(),
                property.acres(),
                property.payableTo(),
                property.businessNumbers(),
                property.remittanceAddress(),
                property.yearBuilt(),
                property.customFields(),
                property.fieldLinks(),
                property.fieldNotes(),
                property.propertyManager(),
                property.createdAt()
        );
    }

}

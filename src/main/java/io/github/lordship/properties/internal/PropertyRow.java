package io.github.lordship.properties.internal;

import io.github.lordship.properties.BusinessNumber;
import io.github.lordship.properties.Property;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PropertyRow(
        UUID uuid,
        String propertyCode,
        String propertyName,
        String titleLink,
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
        UUID propertyManager,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {

    public Property toProperty() {
        return new Property(
                this.uuid,
                this.propertyCode,
                this.propertyName,
                this.titleLink,
                this.propertyStreet,
                this.propertyCity,
                this.propertyState,
                this.propertyZip,
                this.propertyCounty,
                this.latitude,
                this.longitude,
                this.purchaseDate,
                this.propertyZoning,
                this.propertyParcels,
                this.acres,
                this.payableTo,
                this.businessNumbers,
                this.remittanceAddress,
                this.yearBuilt,
                this.customFields,
                this.fieldLinks,
                this.fieldNotes,
                this.propertyManager,
                this.createdAt,
                this.deletedAt
        );
    }

    // The fields a new property needs. Everything else is filled in later.
    public PropertyRow(String propertyName, String propertyStreet, String propertyCity,
                       String propertyState, String propertyZip) {
        this(
                null,
                null,
                propertyName,
                null,
                propertyStreet,
                propertyCity,
                propertyState,
                propertyZip,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                List.of(),
                null,
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                null,
                null
        );
    }

    // A row before the database has set createdAt and deletedAt.
    public PropertyRow(UUID uuid, String propertyCode, String propertyName, String propertyStreet,
                       String propertyCity, String propertyState, String propertyZip,
                       LocalDate purchaseDate, String propertyZoning, List<String> propertyParcels,
                       String payableTo, String remittanceAddress, Integer yearBuilt) {
        this(
                uuid,
                propertyCode,
                propertyName,
                null,
                propertyStreet,
                propertyCity,
                propertyState,
                propertyZip,
                null,
                null,
                null,
                purchaseDate,
                propertyZoning,
                propertyParcels,
                null,
                payableTo,
                List.of(),
                remittanceAddress,
                yearBuilt,
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                null,
                null
        );
    }
}

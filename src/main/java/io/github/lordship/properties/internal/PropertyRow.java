package io.github.lordship.properties.internal;

import io.github.lordship.properties.Property;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record PropertyRow(
        UUID uuid,
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
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {

    public Property toProperty() {
        return new Property(
                this.uuid,
                this.propertyCode,
                this.propertyName,
                this.propertyStreet,
                this.propertyCity,
                this.propertyState,
                this.propertyZip,
                this.purchaseDate,
                this.propertyZoning,
                this.propertyParcel,
                this.payableTo,
                this.remittanceAddress,
                this.yearBuilt,
                this.customFields,
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
                propertyStreet,
                propertyCity,
                propertyState,
                propertyZip,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                null,
                null
        );
    }

    // A row before the database has set createdAt and deletedAt.
    public PropertyRow(UUID uuid, String propertyCode, String propertyName, String propertyStreet,
                       String propertyCity, String propertyState, String propertyZip,
                       LocalDate purchaseDate, String propertyZoning, String propertyParcel, String payableTo, String remittanceAddress, Integer yearBuilt) {
        this(
                uuid,
                propertyCode,
                propertyName,
                propertyStreet,
                propertyCity,
                propertyState,
                propertyZip,
                purchaseDate,
                propertyZoning,
                propertyParcel,
                payableTo,
                remittanceAddress,
                yearBuilt,
                Map.of(),
                null,
                null
        );
    }
}

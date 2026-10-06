package io.github.lordship.properties;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record Property(
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
        UUID propertyManager,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {}

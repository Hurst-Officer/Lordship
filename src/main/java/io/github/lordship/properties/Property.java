package io.github.lordship.properties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record Property(
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
) {}

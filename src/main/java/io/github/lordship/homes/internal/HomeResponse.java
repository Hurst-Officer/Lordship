package io.github.lordship.homes.internal;

import io.github.lordship.homes.Home;
import io.github.lordship.homes.HomeCondition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record HomeResponse(
        UUID id,
        String name,
        UUID lotId,
        BigDecimal estimatedValue,
        LocalDate estimatedValueOn,
        Integer modelYear,
        String make,
        String model,
        Integer bedroomCount,
        BigDecimal bathroomCount,
        BigDecimal width,
        BigDecimal length,
        String dimensionsUnits,
        Integer sections,
        HomeCondition condition,
        String appearance,
        String note,
        String parcel,
        String vin,
        Boolean parkOwned,
        OffsetDateTime createdAt,
        UUID createdBy
) {

    public static HomeResponse from(Home home) {
        return new HomeResponse(
                home.uuid(),
                home.name(),
                home.lotId(),
                home.estimatedValue(),
                home.estimatedValueOn(),
                home.modelYear(),
                home.make(),
                home.model(),
                home.bedroomCount(),
                home.bathroomCount(),
                home.width(),
                home.length(),
                home.dimensionsUnits(),
                home.sections(),
                home.condition(),
                home.appearance(),
                home.note(),
                home.parcel(),
                home.vin(),
                home.parkOwned(),
                home.createdAt(),
                home.createdBy()
        );
    }
}

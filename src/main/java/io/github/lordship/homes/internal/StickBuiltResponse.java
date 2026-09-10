package io.github.lordship.homes.internal;

import io.github.lordship.homes.StickBuilt;
import io.github.lordship.homes.StructureType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record StickBuiltResponse(
        UUID uuid,
        String name,
        UUID lotId,
        Integer yearBuilt,
        StructureType structureType,
        Integer floor,
        Integer bedroomCount,
        BigDecimal bathroomCount,
        BigDecimal area,
        String areaUnits,
        String appearance,
        Boolean parkOwned,
        String note,
        OffsetDateTime createdAt,
        UUID createdBy
) {

    public static StickBuiltResponse from(StickBuilt stickBuilt) {
        return new StickBuiltResponse(
                stickBuilt.uuid(),
                stickBuilt.name(),
                stickBuilt.lotId(),
                stickBuilt.yearBuilt(),
                stickBuilt.structureType(),
                stickBuilt.floor(),
                stickBuilt.bedroomCount(),
                stickBuilt.bathroomCount(),
                stickBuilt.area(),
                stickBuilt.areaUnits(),
                stickBuilt.appearance(),
                stickBuilt.parkOwned(),
                stickBuilt.note(),
                stickBuilt.createdAt(),
                stickBuilt.createdBy()
        );
    }
}

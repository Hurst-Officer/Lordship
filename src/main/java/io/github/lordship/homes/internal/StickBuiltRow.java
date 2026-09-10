package io.github.lordship.homes.internal;

import io.github.lordship.homes.StickBuilt;
import io.github.lordship.homes.StructureType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record StickBuiltRow(
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
        UUID createdBy,
        OffsetDateTime deletedAt
) {
    public StickBuilt toStickBuilt() {
        return new StickBuilt(
                this.uuid,
                this.name,
                this.lotId,
                this.yearBuilt,
                this.structureType,
                this.floor,
                this.bedroomCount,
                this.bathroomCount,
                this.area,
                this.areaUnits,
                this.appearance,
                this.parkOwned,
                this.note,
                this.createdAt,
                this.createdBy,
                this.deletedAt
        );
    }

    // Convenience constructor for insert
    public StickBuiltRow(UUID lotId, UUID createdBy) {
        this(
                null,
                null,
                lotId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                createdBy,
                null
        );
    }
}

package io.github.lordship.homes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record StickBuilt(
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
    public boolean isSoftDeleted() {
        return deletedAt != null;
    }
}

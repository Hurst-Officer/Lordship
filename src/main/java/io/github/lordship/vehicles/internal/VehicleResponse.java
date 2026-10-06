package io.github.lordship.vehicles.internal;

import io.github.lordship.vehicles.Vehicle;

import java.time.OffsetDateTime;
import java.util.UUID;

public record VehicleResponse(
        UUID id,
        UUID tenancyId,
        String make,
        String model,
        Integer year,
        String plateNumber,
        String plateState,
        String color,
        String notes,
        OffsetDateTime createdAt
) {

    public static VehicleResponse from(Vehicle vehicle) {
        return new VehicleResponse(
                vehicle.uuid(),
                vehicle.tenancyUuid(),
                vehicle.make(),
                vehicle.model(),
                vehicle.year(),
                vehicle.plateNumber(),
                vehicle.plateState(),
                vehicle.color(),
                vehicle.notes(),
                vehicle.createdAt()
        );
    }

}

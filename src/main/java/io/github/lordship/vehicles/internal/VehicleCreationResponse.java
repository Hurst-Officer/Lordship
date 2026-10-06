package io.github.lordship.vehicles.internal;

import java.util.List;

public record VehicleCreationResponse(
        VehicleResponse vehicle,
        boolean plateConflictFlagged,
        List<VehicleResponse> conflictingVehicles
) {

    public static VehicleCreationResponse from(VehicleCreationResult result) {
        return new VehicleCreationResponse(
                VehicleResponse.from(result.vehicle()),
                result.plateConflictFlagged(),
                result.conflictingVehicles().stream().map(VehicleResponse::from).toList()
        );
    }
}
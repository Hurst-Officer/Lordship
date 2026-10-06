package io.github.lordship.vehicles.internal;

import io.github.lordship.vehicles.VehicleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {
    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @PreAuthorize("hasAuthority('vehicles:create')")
    @PostMapping("/create")
    public ResponseEntity<VehicleCreationResponse> createVehicle(
            @Valid @RequestBody VehicleCreateRequest request) {
        VehicleCreationResult result = vehicleService.registerVehicle(request.tenancyId(), request.plateNumber());
        return ResponseEntity.status(HttpStatus.CREATED).body(VehicleCreationResponse.from(result));
    }

    @PreAuthorize("hasAuthority('vehicles:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<VehicleResponse> getVehicle(@PathVariable UUID uuid) {
        return vehicleService.findById(uuid)
                .map(VehicleResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('vehicles:view')")
    @GetMapping("/bytenancy/{tenancyUuid}")
    public ResponseEntity<List<VehicleResponse>> getVehiclesByTenancy(@PathVariable UUID tenancyUuid) {
        return ResponseEntity.ok(vehicleService.findByTenancy(tenancyUuid).stream().map(VehicleResponse::from).toList());
    }

    @PreAuthorize("hasAuthority('vehicles:view')")
    @GetMapping("/byproperty/{propertyUuid}")
    public ResponseEntity<List<VehicleResponse>> getVehiclesByProperty(@PathVariable UUID propertyUuid) {
        return ResponseEntity.ok(vehicleService.findByProperty(propertyUuid).stream().map(VehicleResponse::from).toList());
    }

    @PreAuthorize("hasAuthority('vehicles:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<VehicleResponse> patchVehicle(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();
        if (request.containsKey("make"))        changes.put("make", request.get("make"));
        if (request.containsKey("model"))       changes.put("model", request.get("model"));
        if (request.containsKey("year"))        changes.put("year", request.get("year"));
        if (request.containsKey("plateNumber")) changes.put("plate_number", request.get("plateNumber"));
        if (request.containsKey("plateState"))  changes.put("plate_state", request.get("plateState"));
        if (request.containsKey("color"))       changes.put("color", request.get("color"));
        if (request.containsKey("notes"))       changes.put("notes", request.get("notes"));

        return vehicleService.patchVehicle(uuid, changes)
                .map(VehicleResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('vehicles:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteVehicle(@PathVariable UUID uuid) {
        return vehicleService.deleteVehicle(uuid) ?
                ResponseEntity.noContent().build() :
                ResponseEntity.notFound().build();
    }

}

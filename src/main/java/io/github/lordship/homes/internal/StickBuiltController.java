package io.github.lordship.homes.internal;

import io.github.lordship.homes.StickBuilt;
import io.github.lordship.homes.StickBuiltService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/stick-builts")
public class StickBuiltController {

    public record StickBuiltCreateRequest(
            @NotNull
            UUID lotId
    ) { }

    private final StickBuiltService stickBuiltService;

    public StickBuiltController(StickBuiltService stickBuiltService) {
        this.stickBuiltService = stickBuiltService;
    }

    @PreAuthorize("hasAuthority('homes:create')")
    @PostMapping
    public ResponseEntity<StickBuiltResponse> createStickBuilt(@Valid @RequestBody StickBuiltCreateRequest request) {
        StickBuilt stickBuilt = stickBuiltService.createStickBuilt(request.lotId());
        return ResponseEntity.status(HttpStatus.CREATED).body(StickBuiltResponse.from(stickBuilt));
    }

    // Exactly one filter. No vin here, unlike homes -- a stick built is real property
    // and carries no serial. A lot holds at most one, so that branch returns a list of
    // one or none rather than a bare object, to keep the endpoint's shape stable.
    @PreAuthorize("hasAuthority('homes:view')")
    @GetMapping
    public ResponseEntity<List<StickBuiltResponse>> listStickBuilts(
            @RequestParam(value = "property", required = false) String propertyCode,
            @RequestParam(value = "lot", required = false) UUID lotId) {

        if ((propertyCode == null) == (lotId == null)) {
            throw new IllegalArgumentException("Give exactly one of property or lot");
        }

        List<StickBuilt> found = propertyCode != null
                ? stickBuiltService.findByProperty(propertyCode)
                : stickBuiltService.findByLot(lotId).map(List::of).orElse(List.of());

        return ResponseEntity.ok(found.stream().map(StickBuiltResponse::from).toList());
    }

    @PreAuthorize("hasAuthority('homes:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<StickBuiltResponse> getStickBuilt(@PathVariable UUID uuid) {
        return stickBuiltService.findById(uuid)
                .map(StickBuiltResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('homes:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<StickBuiltResponse> patchStickBuilt(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();

        if (request.containsKey("name"))           changes.put("name", request.get("name"));
        if (request.containsKey("lotId"))          changes.put("lot_id", request.get("lotId"));
        if (request.containsKey("yearBuilt"))      changes.put("year_built", request.get("yearBuilt"));
        if (request.containsKey("structureType"))  changes.put("structure_type", request.get("structureType"));
        if (request.containsKey("floor"))          changes.put("floor", request.get("floor"));
        if (request.containsKey("bedroomCount"))   changes.put("bedroom_count", request.get("bedroomCount"));
        if (request.containsKey("bathroomCount"))  changes.put("bathroom_count", request.get("bathroomCount"));
        if (request.containsKey("area"))           changes.put("area", request.get("area"));
        if (request.containsKey("areaUnits"))      changes.put("area_units", request.get("areaUnits"));
        if (request.containsKey("appearance"))     changes.put("appearance", request.get("appearance"));
        if (request.containsKey("parkOwned"))      changes.put("park_owned", request.get("parkOwned"));
        if (request.containsKey("note"))           changes.put("note", request.get("note"));

        return stickBuiltService.patchStickBuilt(uuid, changes)
                .map(StickBuiltResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('homes:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteStickBuilt(@PathVariable UUID uuid) {
        return stickBuiltService.deleteStickBuilt(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // No local @ExceptionHandler: ApiExceptionHandler now maps IllegalArgumentException
    // to a 400 with the message, so repeating it here would only shadow it.
}

package io.github.lordship.homes.internal;

import io.github.lordship.homes.Home;
import io.github.lordship.homes.HomeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/homes")
public class HomeController {

    public record HomeCreateRequest(
            @NotNull
            UUID lotId
    ) { }


    private final HomeService homeService;

    public HomeController(HomeService homeService) {
        this.homeService = homeService;
    }

    @PreAuthorize("hasAuthority('homes:create')")
    @PostMapping
    public ResponseEntity<HomeResponse> createHome(@Valid @RequestBody HomeCreateRequest request) {
        Home home = homeService.createHome(request.lotId());
        return ResponseEntity.status(HttpStatus.CREATED).body(HomeResponse.from(home));
    }

    // Exactly one filter: property feeds the park view, lot the lot detail panel,
    // vin the "a title turned up, whose is this" lookup.
    @PreAuthorize("hasAuthority('homes:view')")
    @GetMapping
    public ResponseEntity<List<HomeResponse>> listHomes(
            @RequestParam(value = "property", required = false) String propertyCode,
            @RequestParam(value = "lot", required = false) UUID lotId,
            @RequestParam(value = "vin", required = false) String vin) {

        if (Stream.of(propertyCode, lotId, vin).filter(Objects::nonNull).count() != 1) {
            throw new IllegalArgumentException("Give exactly one of property, lot or vin");
        }

        List<Home> found;
        if (propertyCode != null) {
            found = homeService.findByProperty(propertyCode);
        } else if (lotId != null) {
            found = homeService.findByLot(lotId);
        } else {
            found = homeService.findByVin(vin);
        }

        return ResponseEntity.ok(found.stream().map(HomeResponse::from).toList());
    }

    @PreAuthorize("hasAuthority('homes:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<HomeResponse> getHome(@PathVariable UUID uuid) {
        return homeService.findById(uuid)
                .map(HomeResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('homes:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<HomeResponse> patchHome(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();

        if (request.containsKey("name"))             changes.put("name", request.get("name"));
        if (request.containsKey("lotId"))            changes.put("lot_id", request.get("lotId"));
        if (request.containsKey("estimatedValue"))   changes.put("estimated_value", request.get("estimatedValue"));
        if (request.containsKey("estimatedValueOn")) changes.put("estimated_value_on", request.get("estimatedValueOn"));
        if (request.containsKey("modelYear"))        changes.put("model_year", request.get("modelYear"));
        if (request.containsKey("make"))             changes.put("make", request.get("make"));
        if (request.containsKey("model"))            changes.put("model", request.get("model"));
        if (request.containsKey("bedroomCount"))     changes.put("bedroom_count", request.get("bedroomCount"));
        if (request.containsKey("bathroomCount"))    changes.put("bathroom_count", request.get("bathroomCount"));
        if (request.containsKey("width"))            changes.put("width", request.get("width"));
        if (request.containsKey("length"))           changes.put("length", request.get("length"));
        if (request.containsKey("dimensionsUnits"))  changes.put("dimensions_units", request.get("dimensionsUnits"));
        if (request.containsKey("sections"))         changes.put("sections", request.get("sections"));
        if (request.containsKey("condition"))        changes.put("condition", request.get("condition"));
        if (request.containsKey("appearance"))       changes.put("appearance", request.get("appearance"));
        if (request.containsKey("note"))             changes.put("note", request.get("note"));
        if (request.containsKey("parcel"))           changes.put("parcel", request.get("parcel"));
        if (request.containsKey("vin"))              changes.put("vin", request.get("vin"));
        if (request.containsKey("parkOwned"))        changes.put("park_owned", request.get("parkOwned"));

        return homeService.patchHome(uuid, changes)
                .map(HomeResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('homes:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteHome(@PathVariable UUID uuid) {
        return homeService.deleteHome(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // Carries the message: an unknown condition or a bad filter combination is worth
    // naming, and an empty 400 throws that away.
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }
}

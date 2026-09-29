package io.github.lordship.properties.internal;

import io.github.lordship.properties.Property;
import io.github.lordship.properties.PropertyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

@RestController
@RequestMapping("/api/properties")
public class PropertyController {

    private final PropertyService propertyService;

    public PropertyController(PropertyService propertyService) {
        this.propertyService = propertyService;
    }

    @PreAuthorize("hasAuthority('properties:create')")
    @PostMapping("/create")
    ResponseEntity<Property> createProperty(@Valid @RequestBody PropertyCreateRequest request) {
        Property property = propertyService.createProperty(
                request.propertyName(), request.propertyStreet(), request.propertyCity(),
                request.propertyState(), request.propertyZip());
        return new ResponseEntity<>(property, HttpStatus.CREATED);
    }

    @PreAuthorize("hasAuthority('properties:view')")
    @GetMapping("/{propertyUuid}")
    ResponseEntity<Property> getProperty(@PathVariable UUID propertyUuid) {
        return propertyService.findByPropertyId(propertyUuid)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('properties:view')")
    @GetMapping("/getAll")
    public ResponseEntity<List<Property>> getAllProperties() {
        return ResponseEntity.ok(propertyService.findAll());
    }

    @PreAuthorize("hasAuthority('properties:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<Property> patchProperty(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();

        if (request.containsKey("propertyCode"))    changes.put("property_code", request.get("propertyCode"));
        if (request.containsKey("propertyName"))    changes.put("property_name", request.get("propertyName"));
        if (request.containsKey("propertyStreet"))  changes.put("property_street", request.get("propertyStreet"));
        if (request.containsKey("propertyCity"))    changes.put("property_city", request.get("propertyCity"));
        if (request.containsKey("propertyState"))   changes.put("property_state", request.get("propertyState"));
        if (request.containsKey("propertyZip"))     changes.put("property_zip", request.get("propertyZip"));
        if (request.containsKey("propertyZoning"))  changes.put("property_zoning", request.get("propertyZoning"));
        if (request.containsKey("payableTo"))       changes.put("payable_to", request.get("payableTo"));
        if (request.containsKey("remittanceAddress"))  changes.put("remittance_address", request.get("remittanceAddress"));
        if (request.containsKey("yearBuilt"))       changes.put("year_built", request.get("yearBuilt"));
        if (request.containsKey("propertyParcel"))  changes.put("property_parcel", request.get("propertyParcel"));
        if (request.containsKey("propertyManager")) changes.put("property_manager", request.get("propertyManager"));
        if (request.containsKey("customFields"))    changes.put("custom_fields", request.get("customFields"));


        if (request.containsKey("purchaseDate")) {
            Object rawDate = request.get("purchaseDate");
            if (rawDate instanceof String dateStr && !dateStr.isBlank()) {
                try {
                    changes.put("purchase_date", LocalDate.parse(dateStr));
                } catch (DateTimeParseException e) {
                    return ResponseEntity.badRequest().build(); // Gracefully handle malformed dates
                }
            } else {
                // Allows explicitly clearing the date in the DB if null is passed
                changes.put("purchase_date", null);
            }
        }

        return propertyService.patchProperty(uuid, changes)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('properties:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteProperty(@PathVariable UUID uuid) {
        return propertyService.deleteProperty(uuid) ?
                ResponseEntity.noContent().build() :
                ResponseEntity.notFound().build();
    }
}
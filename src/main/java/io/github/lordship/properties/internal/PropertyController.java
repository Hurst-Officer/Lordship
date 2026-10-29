package io.github.lordship.properties.internal;

import io.github.lordship.properties.Property;
import io.github.lordship.properties.PropertyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
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
    ResponseEntity<PropertyResponse> createProperty(@Valid @RequestBody PropertyCreateRequest request) {
        Property property = propertyService.createProperty(
                request.propertyName(), request.propertyStreet(), request.propertyCity(),
                request.propertyState(), request.propertyZip());
        return new ResponseEntity<>(PropertyResponse.from(property), HttpStatus.CREATED);
    }

    @PreAuthorize("hasAuthority('properties:view')")
    @GetMapping("/{propertyUuid}")
    ResponseEntity<PropertyResponse> getProperty(@PathVariable UUID propertyUuid) {
        return propertyService.findByPropertyId(propertyUuid)
                .map(PropertyResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Every live property, sorted by name.
    @PreAuthorize("hasAuthority('properties:view')")
    @GetMapping
    public ResponseEntity<List<PropertyResponse>> getAllProperties() {
        return ResponseEntity.ok(propertyService.findAll().stream().map(PropertyResponse::from).toList());
    }

    @PreAuthorize("hasAuthority('properties:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<PropertyResponse> patchProperty(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();

        if (request.containsKey("propertyCode"))    changes.put("property_code", request.get("propertyCode"));
        if (request.containsKey("propertyName"))    changes.put("property_name", request.get("propertyName"));
        if (request.containsKey("titleLink")) changes.put("title_link", request.get("titleLink"));
        if (request.containsKey("propertyStreet"))  changes.put("property_street", request.get("propertyStreet"));
        if (request.containsKey("propertyCity"))    changes.put("property_city", request.get("propertyCity"));
        if (request.containsKey("propertyState"))   changes.put("property_state", request.get("propertyState"));
        if (request.containsKey("propertyZip"))     changes.put("property_zip", request.get("propertyZip"));
        if (request.containsKey("propertyCounty"))  changes.put("property_county", request.get("propertyCounty"));
        if (request.containsKey("propertyZoning"))  changes.put("property_zoning", request.get("propertyZoning"));
        if (request.containsKey("payableTo"))       changes.put("payable_to", request.get("payableTo"));
        if (request.containsKey("remittanceAddress"))  changes.put("remittance_address", request.get("remittanceAddress"));
        if (request.containsKey("yearBuilt"))       changes.put("year_built", request.get("yearBuilt"));
        if (request.containsKey("customFields"))    changes.put("custom_fields", request.get("customFields"));

        // Parcels are sent as a whole list of text. Null clears them.
        if (request.containsKey("propertyParcels")) {
            Object raw = request.get("propertyParcels");
            if (raw != null && !(raw instanceof List<?> parcels
                    && parcels.stream().allMatch(parcel -> parcel instanceof String))) {
                return ResponseEntity.badRequest().build();
            }
            changes.put("property_parcels", raw);
        }

        // Business numbers are sent as a whole list of objects. Null clears them.
        // Anything else would save fine but then fail to read back, so it is refused here.
        if (request.containsKey("businessNumbers")) {
            Object raw = request.get("businessNumbers");
            if (raw != null && !(raw instanceof List<?> numbers
                    && numbers.stream().allMatch(number -> number instanceof Map<?, ?>))) {
                return ResponseEntity.badRequest().build();
            }
            changes.put("business_numbers", raw);
        }

        // Acres is a number or null
        if (request.containsKey("acres")) {
            Object raw = request.get("acres");
            if (raw != null && !(raw instanceof Number)) {
                return ResponseEntity.badRequest().build();
            }
            changes.put("acres", raw == null ? null : new BigDecimal(raw.toString()));
        }

        // Latitude and longitude are numbers or null. Set both together, or clear both.
        for (String key : List.of("latitude", "longitude")) {
            if (request.containsKey(key)) {
                Object raw = request.get(key);
                if (raw != null && !(raw instanceof Number)) {
                    return ResponseEntity.badRequest().build();
                }
                changes.put(key, raw == null ? null : ((Number) raw).doubleValue());
            }
        }


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

        // The column is a UUID, so the text from the JSON body must be parsed first.
        // Null or blank clears the property manager.
        if (request.containsKey("propertyManagerId")) {
            Object rawManager = request.get("propertyManagerId");
            if (rawManager instanceof String managerStr && !managerStr.isBlank()) {
                try {
                    changes.put("property_manager", UUID.fromString(managerStr));
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().build();
                }
            } else {
                changes.put("property_manager", null);
            }
        }

        return propertyService.patchProperty(uuid, changes)
                .map(PropertyResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Sets or clears the link that goes with one field.
    // Body: {"field": "property_zoning", "url": "https://..."}. A null or blank url clears it.
    @PreAuthorize("hasAuthority('properties:edit')")
    @PutMapping("/{uuid}/field-links")
    public ResponseEntity<PropertyResponse> setFieldLink(
            @PathVariable UUID uuid,
            @RequestBody FieldLinkRequest request) {
        return propertyService.setFieldLink(uuid, request.field(), request.url())
                .map(PropertyResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Sets or clears the note that goes with one field.
    // Body: {"field": "property_zoning", "note": "..."}. A null or blank note clears it.
    @PreAuthorize("hasAuthority('properties:edit')")
    @PutMapping("/{uuid}/field-notes")
    public ResponseEntity<PropertyResponse> setFieldNote(
            @PathVariable UUID uuid,
            @RequestBody FieldNoteRequest request) {
        return propertyService.setFieldNote(uuid, request.field(), request.note())
                .map(PropertyResponse::from)
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
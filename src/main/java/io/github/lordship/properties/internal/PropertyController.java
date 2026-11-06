package io.github.lordship.properties.internal;

import io.github.lordship.audit.AuditService;
import io.github.lordship.audit.History;
import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.properties.Property;
import io.github.lordship.properties.PropertyService;
import io.github.lordship.shared.InvalidRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

// A property outside the agent's scope (see PropertyScope) is treated as if it did not exist:
// 404 for that property, and left out of lists.
@RestController
@RequestMapping("/api/properties")
public class PropertyController {

    private final PropertyService propertyService;
    private final AuditService auditService;

    public PropertyController(PropertyService propertyService, AuditService auditService) {
        this.propertyService = propertyService;
        this.auditService = auditService;
    }

    // A new property. An agent who only sees their assigned properties is assigned to it,
    // so they can open what they just made. Anyone who is not an agent gets 403.
    @PreAuthorize("hasAuthority('properties:create')")
    @PostMapping("/create")
    ResponseEntity<PropertyResponse> createProperty(@Valid @RequestBody PropertyCreateRequest request,
                                                    Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof AgentPrincipal creator)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Property property = propertyService.createProperty(
                request.propertyName(), request.propertyStreet(), request.propertyCity(),
                request.propertyState(), request.propertyZip(),
                creator.agentUuid(), creator.propertyScope());
        return new ResponseEntity<>(PropertyResponse.from(property), HttpStatus.CREATED);
    }

    @PreAuthorize("hasAuthority('properties:view')")
    @GetMapping("/{propertyUuid}")
    ResponseEntity<PropertyResponse> getProperty(@PathVariable UUID propertyUuid, Authentication authentication) {
        if (!AgentPrincipal.scopeOf(authentication).allows(propertyUuid)) {
            return ResponseEntity.notFound().build();
        }
        return propertyService.findByPropertyId(propertyUuid)
                .map(PropertyResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Every live property the agent may see, sorted by name.
    @PreAuthorize("hasAuthority('properties:view')")
    @GetMapping
    public ResponseEntity<List<PropertyResponse>> getAllProperties(Authentication authentication) {
        return ResponseEntity.ok(propertyService.findAll(AgentPrincipal.scopeOf(authentication)).stream()
                .map(PropertyResponse::from).toList());
    }

    @PreAuthorize("hasAuthority('properties:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<PropertyResponse> patchProperty(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request,
            Authentication authentication) {

        if (!AgentPrincipal.scopeOf(authentication).allows(uuid)) {
            return ResponseEntity.notFound().build();
        }

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

        // Custom fields are sent as one object of labels and values. Null clears them.
        if (request.containsKey("customFields")) {
            Object raw = request.get("customFields");
            if (raw != null && !(raw instanceof Map<?, ?>)) {
                throw InvalidRequest.onField("custom_fields", "property.custom_fields_not_object");
            }
            changes.put("custom_fields", raw == null ? Map.of() : raw);
        }

        // Parcels are sent as a whole list of text. Null clears them.
        if (request.containsKey("propertyParcels")) {
            Object raw = request.get("propertyParcels");
            if (raw != null && !(raw instanceof List<?> parcels
                    && parcels.stream().allMatch(parcel -> parcel instanceof String))) {
                throw InvalidRequest.onField("property_parcels", "property.parcels_not_text");
            }
            changes.put("property_parcels", raw);
        }

        // Business numbers are sent as a whole list of objects. Null clears them.
        // Anything else would save fine but then fail to read back, so it is refused here.
        if (request.containsKey("businessNumbers")) {
            Object raw = request.get("businessNumbers");
            if (raw != null && !(raw instanceof List<?> numbers
                    && numbers.stream().allMatch(number -> number instanceof Map<?, ?>))) {
                throw InvalidRequest.onField("business_numbers", "property.business_numbers_not_list");
            }
            changes.put("business_numbers", raw);
        }

        // Acres is a number or null
        if (request.containsKey("acres")) {
            Object raw = request.get("acres");
            if (raw != null && !(raw instanceof Number)) {
                throw InvalidRequest.onField("acres", "property.not_a_number");
            }
            changes.put("acres", raw == null ? null : new BigDecimal(raw.toString()));
        }

        // Latitude and longitude are numbers or null. Set both together, or clear both.
        for (String key : List.of("latitude", "longitude")) {
            if (request.containsKey(key)) {
                Object raw = request.get(key);
                if (raw != null && !(raw instanceof Number)) {
                    throw InvalidRequest.onField(key, "property.not_a_number");
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
                    throw InvalidRequest.onField("purchase_date", "property.date_invalid");
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
                    throw InvalidRequest.onField("property_manager", "property.manager_not_an_id");
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
            @RequestBody FieldLinkRequest request,
            Authentication authentication) {
        if (!AgentPrincipal.scopeOf(authentication).allows(uuid)) {
            return ResponseEntity.notFound().build();
        }
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
            @RequestBody FieldNoteRequest request,
            Authentication authentication) {
        if (!AgentPrincipal.scopeOf(authentication).allows(uuid)) {
            return ResponseEntity.notFound().build();
        }
        return propertyService.setFieldNote(uuid, request.field(), request.note())
                .map(PropertyResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ---- custom fields and business numbers, one at a time ----
    // Glade saves these one entry at a time, so two people changing different entries keep both changes.
    // 409 when the entry was added, changed or removed by a coworker meanwhile. The whole-list PATCH still works.

    // Adds one custom field. 409 property.custom_field_exists when the label is taken.
    @PreAuthorize("hasAuthority('properties:edit')")
    @PostMapping("/{uuid}/custom-fields")
    public ResponseEntity<PropertyResponse> addCustomField(@PathVariable UUID uuid,
                                                           @RequestBody CustomFieldRequest request,
                                                           Authentication authentication) {
        if (!AgentPrincipal.scopeOf(authentication).allows(uuid)) {
            return ResponseEntity.notFound().build();
        }
        return answer(propertyService.addCustomField(uuid, request.label(), request.value()));
    }

    // Changes one custom field's value. 409 property.custom_field_gone when it was removed meanwhile.
    @PreAuthorize("hasAuthority('properties:edit')")
    @PutMapping("/{uuid}/custom-fields")
    public ResponseEntity<PropertyResponse> changeCustomField(@PathVariable UUID uuid,
                                                              @RequestBody CustomFieldRequest request,
                                                              Authentication authentication) {
        if (!AgentPrincipal.scopeOf(authentication).allows(uuid)) {
            return ResponseEntity.notFound().build();
        }
        return answer(propertyService.changeCustomField(uuid, request.label(), request.value()));
    }

    // Removes one custom field, with its link and note. The label is a query parameter: ?label=Septic
    @PreAuthorize("hasAuthority('properties:edit')")
    @DeleteMapping("/{uuid}/custom-fields")
    public ResponseEntity<PropertyResponse> removeCustomField(@PathVariable UUID uuid,
                                                              @RequestParam String label,
                                                              Authentication authentication) {
        if (!AgentPrincipal.scopeOf(authentication).allows(uuid)) {
            return ResponseEntity.notFound().build();
        }
        return answer(propertyService.removeCustomField(uuid, label));
    }

    // Adds one business number. 409 property.number_exists when the property has it already.
    @PreAuthorize("hasAuthority('properties:edit')")
    @PostMapping("/{uuid}/business-numbers")
    public ResponseEntity<PropertyResponse> addBusinessNumber(@PathVariable UUID uuid,
                                                              @RequestBody BusinessNumberRequest request,
                                                              Authentication authentication) {
        if (!AgentPrincipal.scopeOf(authentication).allows(uuid)) {
            return ResponseEntity.notFound().build();
        }
        return answer(propertyService.addBusinessNumber(uuid, request.state(), request.type(), request.number()));
    }

    // Changes one business number, found by its state, type and number as the worker last saw them.
    // 409 property.number_changed when none matches anymore.
    @PreAuthorize("hasAuthority('properties:edit')")
    @PutMapping("/{uuid}/business-numbers")
    public ResponseEntity<PropertyResponse> changeBusinessNumber(@PathVariable UUID uuid,
                                                                 @RequestBody BusinessNumberChangeRequest request,
                                                                 Authentication authentication) {
        if (!AgentPrincipal.scopeOf(authentication).allows(uuid)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> changes = request.changes() == null ? Map.of() : request.changes();
        return answer(propertyService.changeBusinessNumber(
                uuid, request.state(), request.type(), request.number(), changes));
    }

    // Removes one business number: ?state=WA&type=UBI&number=604 123 456
    @PreAuthorize("hasAuthority('properties:edit')")
    @DeleteMapping("/{uuid}/business-numbers")
    public ResponseEntity<PropertyResponse> removeBusinessNumber(@PathVariable UUID uuid,
                                                                 @RequestParam(required = false) String state,
                                                                 @RequestParam(required = false) String type,
                                                                 @RequestParam String number,
                                                                 Authentication authentication) {
        if (!AgentPrincipal.scopeOf(authentication).allows(uuid)) {
            return ResponseEntity.notFound().build();
        }
        return answer(propertyService.removeBusinessNumber(uuid, state, type, number));
    }

    // The saved property, or 404
    private static ResponseEntity<PropertyResponse> answer(Optional<Property> saved) {
        return saved.map(PropertyResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // A soft delete. The lots stay as they are and are hidden with the property.
    @PreAuthorize("hasAuthority('properties:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteProperty(@PathVariable UUID uuid, Authentication authentication) {
        if (!AgentPrincipal.scopeOf(authentication).allows(uuid)) {
            return ResponseEntity.notFound().build();
        }
        return propertyService.deleteProperty(uuid) ?
                ResponseEntity.noContent().build() :
                ResponseEntity.notFound().build();
    }

    // Brings a deleted property back, with its lots. Answers the property.
    // 404 for an unknown property. 409 when a live property took its code since.
    @PreAuthorize("hasAuthority('properties:delete')")
    @PostMapping("/{uuid}/restore")
    public ResponseEntity<PropertyResponse> restoreProperty(@PathVariable UUID uuid, Authentication authentication) {
        if (!AgentPrincipal.scopeOf(authentication).allows(uuid)) {
            return ResponseEntity.notFound().build();
        }
        return propertyService.restoreProperty(uuid)
                .map(PropertyResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Deleted properties, the most recently deleted first. deletedBy is null without audit:view.
    @PreAuthorize("hasAuthority('properties:delete')")
    @GetMapping("/deleted")
    public ResponseEntity<List<DeletedPropertyResponse>> getDeletedProperties(Authentication authentication) {
        List<Property> properties = propertyService.findDeleted(AgentPrincipal.scopeOf(authentication));
        boolean canViewAudits = authentication.getAuthorities().stream()
                .anyMatch(authority -> "audit:view".equals(authority.getAuthority()));
        Map<UUID, History.Mark> who = canViewAudits
                ? auditService.deletions("property", properties.stream().map(Property::uuid).toList())
                : Map.of();
        return ResponseEntity.ok(properties.stream()
                .map(property -> DeletedPropertyResponse.from(property, who.get(property.uuid())))
                .toList());
    }
}
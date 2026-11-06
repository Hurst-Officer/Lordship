package io.github.lordship.homes.internal;

import io.github.lordship.audit.AuditService;
import io.github.lordship.audit.History;
import io.github.lordship.homes.Home;
import io.github.lordship.homes.HomeService;
import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.PropertyScope;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

// A home in a property outside the agent's scope (see PropertyScope) is treated as if it did not exist:
// 404 for that home, and left out of lists. A lot outside the scope is an unknown lot.
@RestController
@RequestMapping("/api/homes")
public class HomeController {

    public record HomeCreateRequest(
            @NotNull
            UUID lotId
    ) { }


    private final HomeService homeService;
    private final AuditService auditService;

    public HomeController(HomeService homeService, AuditService auditService) {
        this.homeService = homeService;
        this.auditService = auditService;
    }

    @PreAuthorize("hasAuthority('homes:create')")
    @PostMapping
    public ResponseEntity<HomeResponse> createHome(@Valid @RequestBody HomeCreateRequest request,
                                                   Authentication authentication) {
        if (!homeService.lotInScope(request.lotId(), AgentPrincipal.scopeOf(authentication))) {
            throw InvalidRequest.of("home.lot_unknown", request.lotId());
        }
        Home home = homeService.createHome(request.lotId());
        return ResponseEntity.status(HttpStatus.CREATED).body(HomeResponse.from(home));
    }

    // Exactly one filter: property feeds the park view, lot the lot detail panel,
    // vin the "a title turned up, whose is this" lookup.
    @PreAuthorize("hasAuthority('homes:view')")
    @GetMapping
    public ResponseEntity<List<HomeResponse>> listHomes(
            @RequestParam(value = "propertyCode", required = false) String propertyCode,
            @RequestParam(value = "lotId", required = false) UUID lotId,
            @RequestParam(value = "vin", required = false) String vin,
            Authentication authentication) {

        if (Stream.of(propertyCode, lotId, vin).filter(Objects::nonNull).count() != 1) {
            throw InvalidRequest.of("home.filter_needs_exactly_one");
        }

        PropertyScope scope = AgentPrincipal.scopeOf(authentication);
        List<Home> found;
        if (propertyCode != null) {
            found = homeService.propertyCodeInScope(propertyCode, scope)
                    ? homeService.findByProperty(propertyCode)
                    : List.of();
        } else if (lotId != null) {
            found = homeService.lotInScope(lotId, scope)
                    ? homeService.findByLot(lotId)
                    : List.of();
        } else {
            found = homeService.findByVin(vin, scope);
        }

        return ResponseEntity.ok(found.stream().map(HomeResponse::from).toList());
    }

    @PreAuthorize("hasAuthority('homes:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<HomeResponse> getHome(@PathVariable UUID uuid, Authentication authentication) {
        if (!homeService.inScope(uuid, AgentPrincipal.scopeOf(authentication))) {
            return ResponseEntity.notFound().build();
        }
        return homeService.findById(uuid)
                .map(HomeResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('homes:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<HomeResponse> patchHome(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request,
            Authentication authentication) {

        PropertyScope scope = AgentPrincipal.scopeOf(authentication);
        if (!homeService.inScope(uuid, scope)) {
            return ResponseEntity.notFound().build();
        }
        // A lot id is text. Anything else would quietly take the home off its lot.
        // Null or blank takes it off on purpose, like when a home leaves the park.
        Object rawLot = request.get("lotId");
        if (rawLot != null && !(rawLot instanceof String)) {
            throw InvalidRequest.onField("lot_id", "home.lot_unknown", rawLot);
        }
        // a home can only move to a lot the agent may see
        if (rawLot instanceof String newLot && !newLot.isBlank()) {
            UUID newLotId;
            try {
                newLotId = UUID.fromString(newLot);
            } catch (IllegalArgumentException e) {
                throw InvalidRequest.onField("lot_id", "home.lot_unknown", newLot);
            }
            if (!homeService.lotInScope(newLotId, scope)) {
                throw InvalidRequest.onField("lot_id", "home.lot_unknown", newLotId);
            }
        }

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
    public ResponseEntity<Void> deleteHome(@PathVariable UUID uuid, Authentication authentication) {
        if (!homeService.inScope(uuid, AgentPrincipal.scopeOf(authentication))) {
            return ResponseEntity.notFound().build();
        }
        return homeService.deleteHome(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // Brings a deleted home back. Answers the home.
    // 404 for an unknown home. 409 home.lot_deleted while its lot is deleted.
    @PreAuthorize("hasAuthority('homes:delete')")
    @PostMapping("/{uuid}/restore")
    public ResponseEntity<HomeResponse> restoreHome(@PathVariable UUID uuid, Authentication authentication) {
        if (!homeService.inScope(uuid, AgentPrincipal.scopeOf(authentication))) {
            return ResponseEntity.notFound().build();
        }
        return homeService.restoreHome(uuid)
                .map(HomeResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // A lot's deleted homes, the most recently deleted first. deletedBy is null without audit:view.
    @PreAuthorize("hasAuthority('homes:delete')")
    @GetMapping("/deleted")
    public ResponseEntity<List<DeletedHomeResponse>> deletedHomes(@RequestParam("lotId") UUID lotId,
                                                                  Authentication authentication) {
        if (!homeService.lotInScope(lotId, AgentPrincipal.scopeOf(authentication))) {
            return ResponseEntity.notFound().build();
        }
        List<Home> homes = homeService.findDeletedByLot(lotId);
        boolean canViewAudits = authentication.getAuthorities().stream()
                .anyMatch(authority -> "audit:view".equals(authority.getAuthority()));
        Map<UUID, History.Mark> who = canViewAudits
                ? auditService.deletions("mobile_home", homes.stream().map(Home::uuid).toList())
                : Map.of();
        return ResponseEntity.ok(homes.stream().map(home -> DeletedHomeResponse.from(home, who.get(home.uuid()))).toList());
    }

    // No @ExceptionHandler here. A local one would win over ApiExceptionHandler and
    // answer with the diagnostic code instead of the sentence in the worker's language.
}

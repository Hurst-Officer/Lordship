package io.github.lordship.tenants.internal;

import io.github.lordship.tenants.Occupant;
import io.github.lordship.tenants.OccupantService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * People living in a home who are not on the lease. Nothing here reaches
 * billing: an occupant has no account, no transactions and no signature block.
 */
@Validated
@RestController
@RequestMapping("/api/occupants")
public class OccupantController {

    public record OccupantCreateRequest(
            @NotNull
            UUID tenancyId,

            @NotNull
            UUID personId,

            // Optional, ISO yyyy-MM-dd. Omitted, it takes the same default a tenant
            // does, so a household added in one sitting shares one start date.
            LocalDate startDate
    ) { }


    private final OccupantService occupantService;

    public OccupantController(OccupantService occupantService) {
        this.occupantService = occupantService;
    }

    @PreAuthorize("hasAuthority('tenants:create')")
    @PostMapping("/create")
    public ResponseEntity<OccupantResponse> createOccupant(@RequestBody @Valid OccupantCreateRequest request) {
        Occupant occupant = occupantService.create(request.tenancyId(), request.personId(), request.startDate());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(OccupantResponse.from(occupant));
    }

    @PreAuthorize("hasAuthority('tenants:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<OccupantResponse> getById(@PathVariable UUID uuid) {
        return occupantService.findById(uuid)
                .map(o -> ResponseEntity.ok(OccupantResponse.from(o)))
                .orElse(ResponseEntity.notFound().build());
    }

    // Who is in the home. activeOnly=false adds the stays that have already ended.
    @PreAuthorize("hasAuthority('tenants:view')")
    @GetMapping("/tenancy/{tenancyId}")
    public ResponseEntity<List<OccupantResponse>> getByTenancy(
            @PathVariable UUID tenancyId,
            @RequestParam(defaultValue = "true") boolean activeOnly) {

        List<Occupant> occupants = activeOnly
                ? occupantService.findActiveByTenancy(tenancyId)
                : occupantService.findByTenancy(tenancyId);

        return ResponseEntity.ok(occupants.stream().map(OccupantResponse::from).toList());
    }

    @PreAuthorize("hasAuthority('tenants:view')")
    @GetMapping("/person/{personId}")
    public ResponseEntity<List<OccupantResponse>> getByPerson(@PathVariable UUID personId) {
        return ResponseEntity.ok(
                occupantService.findByPerson(personId).stream()
                        .map(OccupantResponse::from)
                        .toList());
    }

    // Moving out is setting endDate, so there is no separate move-out endpoint.
    @PreAuthorize("hasAuthority('tenants:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<OccupantResponse> patchOccupant(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();

        if (request.containsKey("startDate")) {
            changes.put("start_date", request.get("startDate"));
        }

        if (request.containsKey("endDate")) {
            changes.put("end_date", request.get("endDate"));
        }

        return occupantService.patchOccupant(uuid, changes)
                .map(o -> ResponseEntity.ok(OccupantResponse.from(o)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('tenants:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteOccupant(@PathVariable UUID uuid) {
        return occupantService.softDelete(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", String.valueOf(e.getMessage())));
    }
}
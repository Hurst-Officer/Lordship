package io.github.lordship.tenants.internal;

import io.github.lordship.tenants.Tenant;
import io.github.lordship.tenants.TenantService;
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

@Validated
@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    public record TenantCreateRequest(
            @NotNull
            UUID tenancyId,

            @NotNull
            UUID personId,

            // Optional, ISO yyyy-MM-dd. Omitted, the service picks the billing period
            // the office is working in -- see TenantService.defaultStartDate.
            LocalDate startDate
    ) { }

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    // tenancyId and personId are the minimum. startDate may be sent and is
    // otherwise derived; everything else about the stay arrives by PATCH.
    @PreAuthorize("hasAuthority('tenants:create')")
    @PostMapping("/create")
    public ResponseEntity<TenantResponse> createTenant(@RequestBody @Valid TenantCreateRequest request) {
        Tenant tenant = tenantService.create(request.tenancyId(), request.personId(), request.startDate);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TenantResponse.from(tenant));
    }

    @PreAuthorize("hasAuthority('tenants:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<TenantResponse> getById(@PathVariable UUID uuid) {
        return tenantService.findById(uuid)
                .map(t -> ResponseEntity.ok(TenantResponse.from(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    // The household. activeOnly=false adds the stays that have already ended.
    @PreAuthorize("hasAuthority('tenants:view')")
    @GetMapping("/tenancy/{tenancyId}")
    public ResponseEntity<List<TenantResponse>> getByTenancy(
            @PathVariable UUID tenancyId,
            @RequestParam(defaultValue = "true") boolean activeOnly) {

        List<Tenant> tenants = activeOnly
                ? tenantService.findActiveByTenancy(tenancyId)
                : tenantService.findByTenancy(tenancyId);

        return ResponseEntity.ok(tenants.stream().map(TenantResponse::from).toList());
    }

    @PreAuthorize("hasAuthority('tenants:view')")
    @GetMapping("/person/{personId}")
    public ResponseEntity<List<TenantResponse>> getByPerson(@PathVariable UUID personId) {
        return ResponseEntity.ok(
                tenantService.findByPerson(personId).stream()
                        .map(TenantResponse::from)
                        .toList());
    }

    // Moving out is setting endDate, so there is no separate move-out endpoint.
    // Sending endDate null undoes one, which the service refuses when that person
    // is already back on the tenancy under a newer row.
    @PreAuthorize("hasAuthority('tenants:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<TenantResponse> patchTenant(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();

        if (request.containsKey("startDate")) {
            changes.put("start_date", request.get("startDate"));
        }

        if (request.containsKey("endDate")) {
            changes.put("end_date", request.get("endDate"));
        }

        return tenantService.patchTenant(uuid, changes)
                .map(t -> ResponseEntity.ok(TenantResponse.from(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('tenants:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteTenant(@PathVariable UUID uuid) {
        return tenantService.softDelete(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // Refusals are answered by config ApiExceptionHandler: a rule the service refused (tenant.already_on,
    // tenancy.end_before_start...) comes back as { message, code } in the worker's language, 400 or 409.
    // The restore of a deleted stay is in HouseholdController.

    @ExceptionHandler(EntityNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", String.valueOf(e.getMessage())));
    }
}

package io.github.lordship.tenancy.internal;

import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancy.TenancyService;
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
@RequestMapping("/api/tenancy")
public class TenancyController {

    private final TenancyService tenancyService;

    public TenancyController(TenancyService tenancyService) {
        this.tenancyService = tenancyService;
    }

    // startDate is optional (yyyy-MM-dd). If left out, the service picks the
    // billing period the office is working in. See TenancyService.billingPeriodStart.
    public record TenancyCreateRequest(
            @NotNull
            UUID lotId,

            LocalDate startDate
    ) { }


    @PreAuthorize("hasAuthority('tenancy:create')")
    @PostMapping("/create")
    public ResponseEntity<TenancyResponse> createTenancy(@RequestBody @Valid TenancyCreateRequest tenancyCreateRequest) {
        Tenancy tenancy = tenancyService.create(
                tenancyCreateRequest.lotId(), tenancyCreateRequest.startDate());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(TenancyResponse.from(tenancy));
    }

    @PreAuthorize("hasAuthority('tenancy:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<TenancyResponse> getById(@PathVariable UUID uuid) {
        return tenancyService.findTenancyById(uuid)
                .map(t -> ResponseEntity.ok(TenancyResponse.from(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('tenancy:view')")
    @GetMapping("/lot/{lotId}")
    public ResponseEntity<List<TenancyResponse>> getByLot(@PathVariable UUID lotId) {
        List<TenancyResponse> responses = tenancyService.findActiveTenancyByLot(lotId)
                .stream()
                .map(TenancyResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    // Ending a tenancy is setting its endDate, so there is no separate close
    // endpoint. Sending endDate null reopens one, which the service refuses when
    // the lot is already carrying two.
    @PreAuthorize("hasAuthority('tenancy:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<TenancyResponse> patchTenancy(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();

        if (request.containsKey("startDate")) {
            changes.put("start_date", request.get("startDate"));
        }

        if (request.containsKey("endDate")) {
            changes.put("end_date", request.get("endDate"));
        }

        if (request.containsKey("notes")) {
            changes.put("notes", request.get("notes"));
        }

        if (request.containsKey("noPartialPayments")) {
            changes.put("no_partial_payments", request.get("noPartialPayments"));
        }

        if (request.containsKey("noPersonalChecks")) {
            changes.put("no_personal_checks", request.get("noPersonalChecks"));
        }

        if (request.containsKey("acceptPayments")) {
            changes.put("accept_payments", request.get("acceptPayments"));
        }

        if (request.containsKey("exemptFromLateFees")) {
            changes.put("exempt_from_late_fees", request.get("exemptFromLateFees"));
        }

        return tenancyService.patchTenancy(uuid, changes)
                .map(t -> ResponseEntity.ok(TenancyResponse.from(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('tenancy:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteTenancy(@PathVariable UUID uuid) {
        return tenancyService.softDelete(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // "Lot 14 cannot take a new tenancy: condemned after the flood" is the whole
    // content of the refusal, and an empty 409 throws it away.
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
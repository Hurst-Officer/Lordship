package io.github.lordship.tenancyterms.internal;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.tenancyterms.TenancyChargeTermResponse;
import io.github.lordship.tenancyterms.TenancyChargeTermService;
import io.github.lordship.tenancyterms.TenancyTermSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenancy-charge-terms")
public class TenancyChargeTermController {

    private static final Map<String, String> PATCHABLE_COLUMNS = Map.ofEntries(
            Map.entry("validAt", "valid_at"),
            Map.entry("rate", "rate"),
            Map.entry("carFee", "car_fee"),
            Map.entry("allowedCars", "allowed_cars"),
            Map.entry("carsMax", "cars_max"),
            Map.entry("petFee", "pet_fee"),
            Map.entry("allowedPets", "allowed_pets"),
            Map.entry("paymentDueDay", "payment_due_day"),
            Map.entry("gracePeriodDays", "grace_period_days"),
            Map.entry("ruleViolationFeeMethod", "rule_violation_fee_method"),
            Map.entry("ruleViolationFeeAmount", "rule_violation_fee_amount"),
            Map.entry("nsfFeeMethod", "nsf_fee_method"),
            Map.entry("nsfFeeAmount", "nsf_fee_amount"),
            Map.entry("lateFeeMethod", "late_fee_method"),
            Map.entry("lateFeeAmount", "late_fee_amount"),
            Map.entry("waterMethod", "water_method"),
            Map.entry("waterFlatAmount", "water_flat_amount"),
            Map.entry("powerMethod", "power_method"),
            Map.entry("powerFlatAmount", "power_flat_amount"),
            Map.entry("sewerMethod", "sewer_method"),
            Map.entry("sewerFlatAmount", "sewer_flat_amount"),
            Map.entry("trashMethod", "trash_method"),
            Map.entry("trashFlatAmount", "trash_flat_amount"),
            Map.entry("securityDepositMethod", "security_deposit_method"),
            Map.entry("securityDepositAmount", "security_deposit_amount"),
            Map.entry("note", "note"));

    private final TenancyChargeTermService tenancyChargeTermService;

    public TenancyChargeTermController(TenancyChargeTermService tenancyChargeTermService) {
        this.tenancyChargeTermService = tenancyChargeTermService;
    }

    // Only for MIGRATION and CORRECTION terms. Every other term is created from
    // its document: POST /api/instruments/{uuid}/charge-terms/schedule.
    // correctionReason is required for CORRECTION and not allowed otherwise.
    public record CreateChargeTermRequest (
            @NotNull UUID tenancy,
            @NotNull AgreementType agreementType,
            @NotNull LocalDate validAt,
            @NotNull TenancyTermSource source,
            String correctionReason) {}


    public record CancelChargeTermRequest(@NotBlank String cancelReason) {}

    // The deal history for one tenancy, newest first.
    @PreAuthorize("hasAuthority('tenancy_term:view')")
    @GetMapping
    public ResponseEntity<List<TenancyChargeTermResponse>> listByTenancy(
            @RequestParam("tenancy") UUID tenancy) {
        return ResponseEntity.ok(
                tenancyChargeTermService.findByTenancy(tenancy).stream()
                        .map(TenancyChargeTermResponse::from)
                        .toList());
    }

    // What billing asks: the term in force on the first day of the period.
    @PreAuthorize("hasAuthority('tenancy_term:view')")
    @GetMapping("/in-force")
    public ResponseEntity<TenancyChargeTermResponse> getInForceOn(
            @RequestParam("tenancy") UUID tenancy,
            @RequestParam("on") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {

        return tenancyChargeTermService.findInForceOn(tenancy, on)
                .map(TenancyChargeTermResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('tenancy_term:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<TenancyChargeTermResponse> getChargeTerm(@PathVariable UUID uuid) {
        return tenancyChargeTermService.findById(uuid)
                .map(TenancyChargeTermResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Admin only. The same permission covers migrations and corrections.
    @PreAuthorize("hasAuthority('tenancy_term:create_migrations')")
    @PostMapping
    public ResponseEntity<TenancyChargeTermResponse> createChargeTerm(
            @Valid @RequestBody CreateChargeTermRequest request) {

        return tenancyChargeTermService.createFromTemplate(
                        request.tenancy(),
                        request.agreementType(),
                        request.validAt(),
                        request.source(),
                        request.correctionReason())
                .map(TenancyChargeTermResponse::from)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('tenancy_term:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<TenancyChargeTermResponse> patchChargeTerm(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();
        PATCHABLE_COLUMNS.forEach((jsonField, column) -> {
            if (request.containsKey(jsonField)) {
                changes.put(column, request.get(jsonField));
            }
        });

        return tenancyChargeTermService.patchChargeTerm(uuid, changes)
                .map(TenancyChargeTermResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // The draft is finished and a document is going out. 400 carries the list
    // of fields that are not ready.
    @PreAuthorize("hasAuthority('tenancy_term:edit')")
    @PostMapping("/{uuid}/submit")
    public ResponseEntity<TenancyChargeTermResponse> submitChargeTerm(@PathVariable UUID uuid) {
        return tenancyChargeTermService.submit(uuid)
                .map(TenancyChargeTermResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // The document came back signed or served; the deal is in force from valid_at.
    @PreAuthorize("hasAuthority('tenancy_term:activate')")
    @PostMapping("/{uuid}/activate")
    public ResponseEntity<TenancyChargeTermResponse> activateChargeTerm(@PathVariable UUID uuid) {
        return tenancyChargeTermService.activate(uuid)
                .map(TenancyChargeTermResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // There is no endpoint for linking a term to a document. A term is created
    // already linked, by POST /api/instruments/{uuid}/charge-terms/schedule.

    // Ends a term that has gone into force. Returns 404 for a term that never
    // went into force. Delete that one instead.
    @PreAuthorize("hasAuthority('tenancy_term:cancel')")
    @PostMapping("/{uuid}/cancel")
    public ResponseEntity<TenancyChargeTermResponse> cancelChargeTerm(
            @PathVariable UUID uuid,
            @Valid @RequestBody CancelChargeTermRequest request) {

        return tenancyChargeTermService.cancel(uuid, request.cancelReason())
                .map(TenancyChargeTermResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Only for a term that never generated charges. An in-force term answers
    // 404 rather than being removed.
    @PreAuthorize("hasAuthority('tenancy_term:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteChargeTerm(@PathVariable UUID uuid) {
        return tenancyChargeTermService.deleteChargeTerm(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // No local @ExceptionHandler anymore. These used to exist because a term
    // that is not ready to submit fails against seven method/amount pairs at
    // once and the global handler was swallowing the detail. Now the failure
    // carries its own structure, so ApiExceptionHandler answers with every
    // problem AND the field each one belongs to -- which is more than this
    // could, and in whatever language the request asked for.
}
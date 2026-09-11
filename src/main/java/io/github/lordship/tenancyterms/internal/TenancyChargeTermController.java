package io.github.lordship.tenancyterms.internal;

import io.github.lordship.shared.AgreementType;
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

    // batch is optional -- set it to group one bulk run so it can be reviewed
    // or abandoned together.
    public record CreateChargeTermRequest(
            @NotNull UUID tenancy,
            @NotNull AgreementType agreementType,
            @NotNull LocalDate validAt,
            @NotNull TenancyTermSource source,
            UUID batch) {}

    public record CancelChargeTermRequest(@NotBlank String cancelReason) {}

    public record AttachSourceRequest(@NotNull UUID sourceUuid) {}

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

    // One bulk run, so it can be reviewed or abandoned together.
    @PreAuthorize("hasAuthority('tenancy_term:view')")
    @GetMapping("/batch/{batch}")
    public ResponseEntity<List<TenancyChargeTermResponse>> listByBatch(@PathVariable UUID batch) {
        return ResponseEntity.ok(
                tenancyChargeTermService.findByBatch(batch).stream()
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

    @PreAuthorize("hasAuthority('tenancy_term:create') and "
            + "(#request.source().name() != 'MIGRATION' or hasAuthority('tenancy_term:create_migrations'))")
    @PostMapping
    public ResponseEntity<TenancyChargeTermResponse> createChargeTerm(
            @Valid @RequestBody CreateChargeTermRequest request) {

        return tenancyChargeTermService.createFromTemplate(
                        request.tenancy(),
                        request.agreementType(),
                        request.validAt(),
                        request.source(),
                        request.batch())
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

    // The instrument that produced this deal. Separate from PATCH because the
    // composite foreign key to instrument(uuid, tenancy) is what stops a
    // document from another tenancy being attached.
    @PreAuthorize("hasAuthority('tenancy_term:edit')")
    @PutMapping("/{uuid}/source")
    public ResponseEntity<TenancyChargeTermResponse> attachSource(
            @PathVariable UUID uuid,
            @Valid @RequestBody AttachSourceRequest request) {

        return tenancyChargeTermService.attachSource(uuid, request.sourceUuid())
                .map(TenancyChargeTermResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Ends a term that HAS gone into effect. 404 also covers a term that was
    // never in force -- delete that one instead.
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

    // Unlike the other controllers this one returns the message. A term that is
    // not ready to submit fails against seven method/amount pairs at once, and
    // an empty 400 would leave the office worker guessing which field is wrong.
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", String.valueOf(e.getMessage())));
    }
}
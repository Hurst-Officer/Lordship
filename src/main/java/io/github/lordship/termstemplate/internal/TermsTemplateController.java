package io.github.lordship.termstemplate.internal;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.termstemplate.TermsTemplate;
import io.github.lordship.termstemplate.TermsTemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/terms-templates")
public class TermsTemplateController {

    // JSON field -> column.
    private static final Map<String, String> PATCHABLE_COLUMNS = Map.ofEntries(
            Map.entry("name", "name"),
            Map.entry("targetRate", "target_rate"),
            Map.entry("askingRate", "asking_rate"),
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
            Map.entry("securityDepositMethod", "security_deposit_method"),
            Map.entry("securityDepositAmount", "security_deposit_amount"),
            Map.entry("trashFlatAmount", "trash_flat_amount"),
            Map.entry("note", "note")
    );

    private final TermsTemplateService termsTemplateService;

    public TermsTemplateController(TermsTemplateService termsTemplateService) {
        this.termsTemplateService = termsTemplateService;
    }

    public record CreateGlobalTemplateRequest(
            @NotBlank String name,
            @NotNull AgreementType agreementType) { }

    public record CopyTemplateRequest(@NotNull UUID propertyId) {}

    // The deal types this property may offer. Pass agreementType to narrow to one.
    @PreAuthorize("hasAuthority('terms_template:view')")
    @GetMapping
    public ResponseEntity<List<TermsTemplateResponse>> listByProperty(
            @RequestParam("property") UUID property,
            @RequestParam(value = "agreementType", required = false) AgreementType agreementType) {

        List<TermsTemplate> found = (agreementType == null)
                ? termsTemplateService.findByProperty(property)
                : termsTemplateService.findForProperty(property, agreementType).stream().toList();

        return ResponseEntity.ok(found.stream().map(TermsTemplateResponse::from).toList());
    }

    // Admin-only: the pool properties copy from.
    @PreAuthorize("hasAuthority('terms_template:manage_global')")
    @GetMapping("/global")
    public ResponseEntity<List<TermsTemplateResponse>> listGlobalTemplates() {
        return ResponseEntity.ok(
                termsTemplateService.findGlobalTemplates().stream()
                        .map(TermsTemplateResponse::from)
                        .toList());
    }

    @PreAuthorize("hasAuthority('terms_template:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<TermsTemplateResponse> getStandardTerms(@PathVariable UUID uuid) {
        return termsTemplateService.findById(uuid)
                .map(TermsTemplateResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('terms_template:manage_global')")
    @PostMapping("/global")
    public ResponseEntity<TermsTemplateResponse> createGlobalTemplate(
            @Valid @RequestBody CreateGlobalTemplateRequest request) {

        TermsTemplate created =
                termsTemplateService.createGlobalTemplate(request.name(), request.agreementType());
        return ResponseEntity.status(HttpStatus.CREATED).body(TermsTemplateResponse.from(created));
    }

    // Copying a template in is what authorizes a property to offer that agreement type.
    @PreAuthorize("hasAuthority('terms_template:manage_global')")
    @PostMapping("/{templateId}/copy")
    public ResponseEntity<TermsTemplateResponse> copyTemplateToProperty(
            @PathVariable UUID templateId,
            @Valid @RequestBody CopyTemplateRequest request) {

        return termsTemplateService.copyTemplateToProperty(templateId, request.propertyId())
                .map(TermsTemplateResponse::from)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('terms_template:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<TermsTemplateResponse> patchStandardTerms(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();
        PATCHABLE_COLUMNS.forEach((jsonField, column) -> {
            if (request.containsKey(jsonField)) {
                changes.put(column, request.get(jsonField));
            }
        });

        return termsTemplateService.patchTermsTemplate(uuid, changes)
                .map(TermsTemplateResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('terms_template:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteTermsTemplate(@PathVariable UUID uuid) {
        return termsTemplateService.deleteTermsTemplate(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Void> badRequest() {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Void> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
package io.github.lordship.termstemplate.internal;

import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.PropertyScope;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.termstemplate.TermsTemplate;
import io.github.lordship.termstemplate.TermsTemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Terms templates: the global templates, and the copy each property has for a kind of agreement (the
 * property's deal).
 *
 * <p>Every call follows PropertyScope, like the rest of a property's records. A property's deal outside the
 * agent's properties is answered as if the deal did not exist: 404, or, inside a list of several,
 * terms_template.gone. A global template belongs to no property and is outside the question; the permissions
 * alone decide who may read or change a global template.
 */
@RestController
@RequestMapping("/api/terms-templates")
public class TermsTemplateController {

    // JSON field -> column. A number may arrive as text ("650.00"): the service reads text digit for digit.
    private static final Map<String, String> PATCHABLE_COLUMNS = Map.ofEntries(
            Map.entry("name", "name"),
            Map.entry("targetRate", "target_rate"),
            Map.entry("askingRate", "asking_rate"),
            Map.entry("escalationPercent", "escalation_percent"),
            Map.entry("escalationMonths", "escalation_months"),
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
            Map.entry("lateFeeTiers", "late_fee_tiers"),
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

    // copyFromId: a template or a property's deal whose terms the new template starts from
    // (a property's deal gives every term but the two rates).
    // Left out, the new template starts from the database defaults.
    public record CreateGlobalTemplateRequest(
            @NotBlank String name,
            @NotNull AgreementType agreementType,
            UUID copyFromId) { }

    // The same changes for several templates: ids names the templates, changes has the fields of a single PATCH.
    public record PatchManyRequest(
            @NotEmpty List<UUID> ids,
            @NotNull Map<String, Object> changes) { }

    public record CopyTemplateRequest(@NotNull UUID propertyId) {}

    // The deal types this property may offer. Pass agreementType to narrow to one.
    // 404 for a property outside the agent's scope.
    @PreAuthorize("hasAuthority('terms_template:view')")
    @GetMapping
    public ResponseEntity<List<TermsTemplateResponse>> listByProperty(
            @RequestParam("propertyId") UUID propertyId,
            @RequestParam(value = "agreementType", required = false) AgreementType agreementType,
            Authentication authentication) {

        if (!AgentPrincipal.scopeOf(authentication).allows(propertyId)) {
            return ResponseEntity.notFound().build();
        }

        List<TermsTemplate> found = (agreementType == null)
                ? termsTemplateService.findByProperty(propertyId)
                : termsTemplateService.findForProperty(propertyId, agreementType).stream().toList();

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
    public ResponseEntity<TermsTemplateResponse> getStandardTerms(@PathVariable UUID uuid,
                                                                  Authentication authentication) {
        if (!inScope(uuid, authentication)) {
            return ResponseEntity.notFound().build();
        }
        return termsTemplateService.findById(uuid)
                .map(TermsTemplateResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // With copyFromId the new template starts from that template's or deal's terms; 404 when the source is
    // unknown, or is the deal of a property outside the agent's scope.
    @PreAuthorize("hasAuthority('terms_template:manage_global')")
    @PostMapping("/global")
    public ResponseEntity<TermsTemplateResponse> createGlobalTemplate(
            @Valid @RequestBody CreateGlobalTemplateRequest request, Authentication authentication) {

        if (request.copyFromId() != null) {
            if (!inScope(request.copyFromId(), authentication)) {
                return ResponseEntity.notFound().build();
            }
            return termsTemplateService
                    .createGlobalTemplateFrom(request.name(), request.agreementType(), request.copyFromId())
                    .map(TermsTemplateResponse::from)
                    .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
                    .orElse(ResponseEntity.notFound().build());
        }

        TermsTemplate created =
                termsTemplateService.createGlobalTemplate(request.name(), request.agreementType());
        return ResponseEntity.status(HttpStatus.CREATED).body(TermsTemplateResponse.from(created));
    }

    // Copying a template in is what authorizes a property to offer that agreement type.
    // 404 for a property outside the agent's scope.
    @PreAuthorize("hasAuthority('terms_template:manage_global')")
    @PostMapping("/{templateId}/copy")
    public ResponseEntity<TermsTemplateResponse> copyTemplateToProperty(
            @PathVariable UUID templateId,
            @Valid @RequestBody CopyTemplateRequest request,
            Authentication authentication) {

        if (!AgentPrincipal.scopeOf(authentication).allows(request.propertyId())) {
            return ResponseEntity.notFound().build();
        }
        return termsTemplateService.copyTemplateToProperty(templateId, request.propertyId())
                .map(TermsTemplateResponse::from)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('terms_template:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<TermsTemplateResponse> patchStandardTerms(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request,
            Authentication authentication) {

        if (!inScope(uuid, authentication)) {
            return ResponseEntity.notFound().build();
        }
        return termsTemplateService.patchTermsTemplate(uuid, columnsOf(request))
                .map(TermsTemplateResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // The same changes to several templates at once (the deals of several properties): all of them, or none.
    // Answers the templates as they are afterwards, in the order of ids. 400 or 409 when the changes are refused
    // for any one template; 409 terms_template.gone when one of the templates was deleted since.
    // A deal of a property outside the agent's scope is answered like a deleted one (terms_template.gone),
    // before anything is changed.
    @PreAuthorize("hasAuthority('terms_template:edit')")
    @PatchMapping
    public ResponseEntity<List<TermsTemplateResponse>> patchManyStandardTerms(
            @Valid @RequestBody PatchManyRequest request, Authentication authentication) {

        PropertyScope scope = AgentPrincipal.scopeOf(authentication);
        if (request.ids().stream().anyMatch(id -> !termsTemplateService.inScope(id, scope))) {
            throw RuleConflict.of("terms_template.gone");
        }
        return ResponseEntity.ok(
                termsTemplateService.patchTermsTemplates(request.ids(), columnsOf(request.changes())).stream()
                        .map(TermsTemplateResponse::from)
                        .toList());
    }

    // The fields of a PATCH body under the names of their columns. A field that is not patchable is left out.
    private static Map<String, Object> columnsOf(Map<String, Object> request) {
        Map<String, Object> changes = new HashMap<>();
        PATCHABLE_COLUMNS.forEach((jsonField, column) -> {
            if (request.containsKey(jsonField)) {
                changes.put(column, request.get(jsonField));
            }
        });
        return changes;
    }

    // Whether the template is one the signed-in agent may see or change (TermsTemplateService.inScope)
    private boolean inScope(UUID uuid, Authentication authentication) {
        return termsTemplateService.inScope(uuid, AgentPrincipal.scopeOf(authentication));
    }

    @PreAuthorize("hasAuthority('terms_template:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteTermsTemplate(@PathVariable UUID uuid, Authentication authentication) {
        if (!inScope(uuid, authentication)) {
            return ResponseEntity.notFound().build();
        }
        return termsTemplateService.deleteTermsTemplate(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // Brings a deleted template back, like an undo. A template that is not deleted is answered as the template is.
    // 404 for an unknown template, or the deal of a property outside the agent's scope.
    // 409 when the property took on another template for the same kind of agreement meanwhile
    // (terms_template.type_taken), or another global template took the name (terms_template.name_taken).
    @PreAuthorize("hasAuthority('terms_template:delete')")
    @PostMapping("/{uuid}/restore")
    public ResponseEntity<TermsTemplateResponse> restoreTermsTemplate(@PathVariable UUID uuid,
                                                                      Authentication authentication) {
        if (!inScope(uuid, authentication)) {
            return ResponseEntity.notFound().build();
        }
        return termsTemplateService.restoreTermsTemplate(uuid)
                .map(TermsTemplateResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // No @ExceptionHandler here. A local one would win over ApiExceptionHandler and answer a refusal with
    // no words at all. ApiExceptionHandler answers with the same statuses (400, 409) and the sentence,
    // which Glade shows under the box that was refused.
}
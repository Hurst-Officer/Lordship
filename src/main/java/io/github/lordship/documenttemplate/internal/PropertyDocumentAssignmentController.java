package io.github.lordship.documenttemplate.internal;

import io.github.lordship.documenttemplate.DocumentAudit;
import io.github.lordship.documenttemplate.DocumentAuditService;
import io.github.lordship.documenttemplate.PropertyDocumentAssignmentService;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which documents a park may generate. Separate from
 * {@code DocumentTemplateController} because assigning is a different act from
 * authoring: one decides what a park can produce, the other decides what the
 * words say, and they are done by different people at different times.
 *
 * <p>Assignment is a reference, not a copy -- an edit to the global document
 * reaches every park assigned to it. A park's own exclusions and added
 * clauses live here too, because they are the same act: deciding what this
 * park's version of the document is.
 */
@RestController
@RequestMapping("/api/property-documents")
public class PropertyDocumentAssignmentController {

    private static final Map<String, String> PATCHABLE_COLUMNS = Map.ofEntries(
            Map.entry("note", "note"));

    private final PropertyDocumentAssignmentService assignmentService;
    private final DocumentAuditService documentAuditService;

    public PropertyDocumentAssignmentController(PropertyDocumentAssignmentService assignmentService,
                                                DocumentAuditService documentAuditService) {
        this.assignmentService = assignmentService;
        this.documentAuditService = documentAuditService;
    }

    // The two types are not supplied: they come off the template, so a caller
    // cannot assign a lease template and call it a notice.
    public record AssignDocumentRequest(
            @NotNull UUID propertyId,
            @NotNull UUID documentTemplateId) { }

    /** Everything this park can generate. The setup screen's whole content. */
    @PreAuthorize("hasAuthority('property_document:view')")
    @GetMapping
    public ResponseEntity<List<PropertyDocumentAssignmentResponse>> listByProperty(
            @RequestParam("property") UUID propertyId) {

        return ResponseEntity.ok(
                assignmentService.findByProperty(propertyId).stream()
                        .map(PropertyDocumentAssignmentResponse::from)
                        .toList());
    }

    /**
     * What this park uses for one kind of deal. Answers with 404 when the park
     * cannot generate that document at all, which is the same question generate
     * asks before it starts.
     */
    @PreAuthorize("hasAuthority('property_document:view')")
    @GetMapping("/resolve")
    public ResponseEntity<PropertyDocumentAssignmentResponse> resolve(
            @RequestParam("property") UUID propertyId,
            @RequestParam("agreementType") AgreementType agreementType,
            @RequestParam("instrumentType") InstrumentType instrumentType) {

        return assignmentService.findForProperty(propertyId, agreementType, instrumentType)
                .map(PropertyDocumentAssignmentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * "Can this park actually generate a complete document for everyone living
     * here?" Measures each assigned document against the distinct deals in
     * force, and reports any configuration whose lease would come out missing a
     * paragraph, with the number of tenancies on it.
     *
     * <p>Empty gaps everywhere is the answer you want. This is the check that
     * catches a park configured differently from the others -- the case the
     * template-only checks cannot see, because they never look at a tenant.
     */
    @PreAuthorize("hasAuthority('property_document:view')")
    @GetMapping("/audit")
    public ResponseEntity<DocumentAudit> audit(@RequestParam("property") UUID propertyId) {
        return ResponseEntity.ok(documentAuditService.auditProperty(propertyId));
    }

    @PreAuthorize("hasAuthority('property_document:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<PropertyDocumentAssignmentResponse> getAssignment(@PathVariable UUID uuid) {
        return assignmentService.findById(uuid)
                .map(PropertyDocumentAssignmentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // 409 when the park already has a document for this kind of deal: "generate
    // the lease" has to resolve to exactly one, and the message names the one
    // already in place so the fix is obvious.
    @PreAuthorize("hasAuthority('property_document:assign')")
    @PostMapping
    public ResponseEntity<PropertyDocumentAssignmentResponse> assign(
            @Valid @RequestBody AssignDocumentRequest request) {

        return assignmentService.assign(request.propertyId(), request.documentTemplateId())
                .map(PropertyDocumentAssignmentResponse::from)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('property_document:assign')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<PropertyDocumentAssignmentResponse> patchAssignment(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();
        PATCHABLE_COLUMNS.forEach((jsonField, column) -> {
            if (request.containsKey(jsonField)) {
                changes.put(column, request.get(jsonField));
            }
        });

        return assignmentService.patchAssignment(uuid, changes)
                .map(PropertyDocumentAssignmentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Stops this park generating that document from now on. Anything already
    // generated keeps its own wording and is untouched.
    @PreAuthorize("hasAuthority('property_document:unassign')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> unassign(@PathVariable UUID uuid) {
        return assignmentService.unassign(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ---- what this park changes ---------------------------------------------

    public record ExcludeSectionRequest(@NotNull UUID sectionId) { }

    public record ExcludeClauseRequest(@NotNull UUID clauseId) { }

    public record AddClauseRequest(@NotNull UUID sectionId) { }

    private static final Map<String, String> CUSTOMIZATION_COLUMNS = Map.ofEntries(
            Map.entry("ordinal", "ordinal"),
            Map.entry("title", "title"),
            Map.entry("body", "body"),
            Map.entry("conditionField", "condition_field"),
            Map.entry("conditionValues", "condition_values"),
            Map.entry("note", "note"));

    /**
     * This park does not use that sub-document -- city sewer, no septic
     * addendum. 409 when the section is required: it exists to satisfy the
     * statute named on it and no park may drop it.
     */
    @PreAuthorize("hasAuthority('property_document:assign')")
    @PostMapping("/{uuid}/exclusions/sections")
    public ResponseEntity<PropertyDocumentAssignmentResponse> excludeSection(
            @PathVariable UUID uuid,
            @Valid @RequestBody ExcludeSectionRequest request) {

        return assignmentService.excludeSection(uuid, request.sectionId())
                .map(PropertyDocumentAssignmentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** This park keeps the section but not that one paragraph of it. */
    @PreAuthorize("hasAuthority('property_document:assign')")
    @PostMapping("/{uuid}/exclusions/clauses")
    public ResponseEntity<PropertyDocumentAssignmentResponse> excludeClause(
            @PathVariable UUID uuid,
            @Valid @RequestBody ExcludeClauseRequest request) {

        return assignmentService.excludeClause(uuid, request.clauseId())
                .map(PropertyDocumentAssignmentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Added empty, filled in by the PATCH below -- the same shape as adding a
    // template clause, so "add clause" is a button on both screens.
    @PreAuthorize("hasAuthority('property_document:assign')")
    @PostMapping("/{uuid}/clauses")
    public ResponseEntity<PropertyDocumentAssignmentResponse> addClause(
            @PathVariable UUID uuid,
            @Valid @RequestBody AddClauseRequest request) {

        return assignmentService.addClause(uuid, request.sectionId())
                .map(PropertyDocumentAssignmentResponse::from)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * The wording of a clause this park wrote. 400 when the body names a token
     * that does not exist, or puts a row token where it cannot resolve -- a
     * park writing lease wording answers to the same rules the template does.
     *
     * <p>Ordinal arrives as a number and stays one: the ordinals are sparse, so
     * 12.5 moves this clause between the template's twelfth and thirteenth and
     * nothing else shifts.
     */
    @PreAuthorize("hasAuthority('property_document:assign')")
    @PatchMapping("/customizations/{customizationUuid}")
    public ResponseEntity<PropertyDocumentAssignmentResponse> patchCustomization(
            @PathVariable UUID customizationUuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = new HashMap<>();
        CUSTOMIZATION_COLUMNS.forEach((jsonField, column) -> {
            if (request.containsKey(jsonField)) {
                changes.put(column, request.get(jsonField));
            }
        });
        if (changes.containsKey("ordinal")) {
            changes.put("ordinal", asOrdinal(changes.get("ordinal")));
        }

        return assignmentService.patchCustomization(customizationUuid, changes)
                .map(PropertyDocumentAssignmentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Undoes one change, putting the document back the way the template wrote it. */
    @PreAuthorize("hasAuthority('property_document:assign')")
    @DeleteMapping("/customizations/{customizationUuid}")
    public ResponseEntity<Void> removeCustomization(@PathVariable UUID customizationUuid) {
        return assignmentService.removeCustomization(customizationUuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // JSON hands us a Double or an Integer; the column is NUMERIC(10,4). Going
    // through the string form keeps 12.5 as 12.5 rather than 12.500000000000002.
    private static BigDecimal asOrdinal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ordinal must be a number");
        }
    }
}

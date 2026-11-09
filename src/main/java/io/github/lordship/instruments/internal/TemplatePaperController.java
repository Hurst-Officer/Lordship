package io.github.lordship.instruments.internal;

import io.github.lordship.documenttemplate.DocumentSection;
import io.github.lordship.documenttemplate.DocumentTemplate;
import io.github.lordship.documenttemplate.DocumentTemplateService;
import io.github.lordship.documenttemplate.PropertyDocumentAssignment;
import io.github.lordship.documenttemplate.PropertyDocumentAssignmentService;
import io.github.lordship.documenttemplate.PropertyDocumentCustomization;
import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.instruments.DocumentFreeze;
import io.github.lordship.instruments.LeaseDocument;
import io.github.lordship.instruments.LeasePreview;
import io.github.lordship.instruments.TokenValues;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A document template on paper, for the author writing it (Glade's Documents concern).
 *
 * <p>The same freeze and the same renderer as a lease, so the numbering, the page breaks, the styles and
 * which clauses print are the ones a lease cut from it will have. Only the figures are missing: no tenancy
 * is involved, so every token stands in the text as written ({{term.rate}}), and the page carries no
 * "draft" stamp. Nothing is saved.
 *
 * <p>Here rather than in documenttemplate because the freeze and the renderer are this module's, and this
 * module already reads documents; documenttemplate cannot read instruments.
 */
@RestController
@RequestMapping("/api/document-templates")
public class TemplatePaperController {

    private final DocumentTemplateService documentTemplateService;
    private final PropertyDocumentAssignmentService assignmentService;

    public TemplatePaperController(DocumentTemplateService documentTemplateService,
                                   PropertyDocumentAssignmentService assignmentService) {
        this.documentTemplateService = documentTemplateService;
        this.assignmentService = assignmentService;
    }

    /**
     * methodValues: the deal's methods by token name ({"term.water_method": "FLAT"}); a method left out is
     * unset, so every clause branching on it is held back (the same rule as the structure preview, and
     * validated the same way: 400 token.*).
     * sectionId (optional): only that sub-document.
     * propertyId (optional): the document as that park uses it, with its exclusions and its own clauses
     * (403 without property_document:view).
     * 404 for an unknown document, a section not in it, or a park outside the agent's properties or not
     * using this document.
     */
    public record PaperRequest(Map<String, String> methodValues, UUID sectionId, UUID propertyId) { }

    @PreAuthorize("hasAuthority('document_template:view')")
    @PostMapping(value = "/{uuid}/paper", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> paper(@PathVariable UUID uuid,
                                        @RequestBody(required = false) PaperRequest request,
                                        Authentication authentication) {
        Map<String, String> methodValues = request == null || request.methodValues() == null
                ? Map.of()
                : request.methodValues();
        // validates the methods and their values (400 for a typo), and finds the document
        if (documentTemplateService.preview(uuid, methodValues).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<DocumentTemplate> found = documentTemplateService.findById(uuid);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        DocumentTemplate document = found.get();

        DocumentSection only = null;
        if (request != null && request.sectionId() != null) {
            only = document.sections().stream()
                    .filter(s -> s.uuid().equals(request.sectionId()) && !s.isSoftDeleted())
                    .findFirst()
                    .orElse(null);
            if (only == null) {
                return ResponseEntity.notFound().build();
            }
        }

        List<PropertyDocumentCustomization> customizations = List.of();
        if (request != null && request.propertyId() != null) {
            // the park's own changes are property_document:view's to see, as on the park's screen
            if (!holds(authentication, "property_document:view")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            if (!AgentPrincipal.scopeOf(authentication).allows(request.propertyId())) {
                return ResponseEntity.notFound().build();
            }
            Optional<PropertyDocumentAssignment> assignment = assignmentService.findForProperty(
                    request.propertyId(), document.agreementType(), document.instrumentType());
            if (assignment.isEmpty() || assignment.get().document() == null
                    || !assignment.get().document().uuid().equals(uuid)) {
                return ResponseEntity.notFound().build();
            }
            customizations = assignment.get().customizations();
        }

        // The whole document is frozen even for one section: a clause may cite one in another section, and
        // its number only comes out of the whole freeze
        DocumentFreeze.Frozen frozen = DocumentFreeze.freeze(
                document.sections(), customizations, List.of(), TokenValues.of(methodValues));
        if (only != null) {
            frozen = onlySection(frozen, only, customizations, TokenValues.of(methodValues));
        }
        LeasePreview paper = new LeasePreview(null, uuid, document.name(), document.version(), frozen);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(LeaseDocument.render(paper, null, document.styles(), false));
    }

    /**
     * The one section out of a whole freeze, found by the clauses it printed (the template's, and the park's
     * own in it). A section that printed nothing has nothing to cite, so it is frozen on its own.
     */
    private static DocumentFreeze.Frozen onlySection(DocumentFreeze.Frozen whole, DocumentSection section,
                                                     List<PropertyDocumentCustomization> customizations,
                                                     TokenValues values) {
        Set<UUID> ours = new HashSet<>();
        section.clauses().forEach(clause -> ours.add(clause.uuid()));
        customizations.stream()
                .filter(c -> c.addsClause() && section.uuid().equals(c.section()))
                .forEach(c -> ours.add(c.uuid()));
        for (DocumentFreeze.FrozenSection frozen : whole.sections()) {
            if (frozen.clauses().stream().anyMatch(clause -> ours.contains(clause.sourceClause()))) {
                return new DocumentFreeze.Frozen(List.of(frozen), whole.unresolved(), whole.omittedRequired(),
                        whole.brokenReferences(), whole.separatedPairs());
            }
        }
        return DocumentFreeze.freeze(List.of(section), customizations, List.of(), values);
    }

    private static boolean holds(Authentication authentication, String permission) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> permission.equals(authority.getAuthority()));
    }
}

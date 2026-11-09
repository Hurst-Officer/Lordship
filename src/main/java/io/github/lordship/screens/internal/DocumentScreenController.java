package io.github.lordship.screens.internal;

import io.github.lordship.audit.AuditService;
import io.github.lordship.audit.History;
import io.github.lordship.documenttemplate.DocumentAuditService;
import io.github.lordship.documenttemplate.DocumentSection;
import io.github.lordship.documenttemplate.DocumentTemplate;
import io.github.lordship.documenttemplate.DocumentTemplateService;
import io.github.lordship.documenttemplate.PropertyDocumentAssignment;
import io.github.lordship.documenttemplate.PropertyDocumentAssignmentService;
import io.github.lordship.documenttemplate.PropertyDocumentCustomization;
import io.github.lordship.documenttemplate.internal.DocumentAuditResponse;
import io.github.lordship.documenttemplate.internal.DocumentTemplateResponse;
import io.github.lordship.documenttemplate.internal.PropertyDocumentAssignmentResponse;
import io.github.lordship.documenttemplate.internal.TokenResponse;
import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.properties.Property;
import io.github.lordship.properties.PropertyService;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.DocumentToken;
import io.github.lordship.shared.InstrumentType;
import io.github.lordship.shared.PropertyScope;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One call per screen of Glade's Documents concern: every document, one document, one section of it, and
 * one document as one park uses it.
 *
 * <p>Documents are global (document_template:view). Which parks use them is property_document:view, and
 * only the parks in the agent's PropertyScope are listed; a park outside it is a 404 like anywhere else.
 * Each part needs the permission its own endpoint needs; without it, that part is null.
 * zone is the viewer's time zone, like America/Los_Angeles. The history's days follow it.
 */
@RestController
@RequestMapping("/api/screens")
public class DocumentScreenController {

    private final DocumentTemplateService documentTemplateService;
    private final PropertyDocumentAssignmentService assignmentService;
    private final DocumentAuditService documentAuditService;
    private final PropertyService propertyService;
    private final AuditService auditService;

    public DocumentScreenController(DocumentTemplateService documentTemplateService,
                                    PropertyDocumentAssignmentService assignmentService,
                                    DocumentAuditService documentAuditService,
                                    PropertyService propertyService,
                                    AuditService auditService) {
        this.documentTemplateService = documentTemplateService;
        this.assignmentService = assignmentService;
        this.documentAuditService = documentAuditService;
        this.propertyService = propertyService;
        this.auditService = auditService;
    }

    // ---- shapes ----------------------------------------------------------------------------

    /**
     * One row of the list of documents.
     * unfinished: methods some clause branches on with values (other than NONE) that no clause covers yet;
     * warnings: clauses whose body prints a figure the clause does not condition on (unguardedTokens).
     * parks: how many of the agent's parks use it (null without property_document:view).
     */
    public record DocumentLine(UUID id, String name, AgreementType agreementType, InstrumentType instrumentType,
                               int version, String note, int sections, int clauses, Integer parks,
                               int unfinished, int warnings, OffsetDateTime createdAt) { }

    // One document a park uses, for the list's parks
    public record ParkDocument(UUID assignmentId, UUID documentId, String documentName,
                               AgreementType agreementType, InstrumentType instrumentType, int changes) { }

    // One park and the documents it uses. A park with none is listed too.
    public record ParkLine(UUID propertyId, String propertyCode, String propertyName, List<ParkDocument> documents) { }

    public record DocumentsScreen(List<DocumentLine> documents, List<ParkLine> parks) { }

    // One park using the open document
    public record DocumentPark(UUID assignmentId, UUID propertyId, String propertyCode, String propertyName,
                               int changes, String note) { }

    /** The document page: the whole document, the parks using it, and its history. */
    public record DocumentScreen(DocumentTemplateResponse document, List<DocumentPark> parks,
                                 History.PageHistory history) { }

    /** The section page: the whole document (numbers and citations reach across sections), which section, the tokens. */
    public record SectionScreen(DocumentTemplateResponse document, UUID sectionId, List<TokenResponse> tokens,
                                History.PageHistory history) { }

    public record ParkRef(UUID id, String propertyCode, String propertyName) { }

    /**
     * The document as one park uses it. assignment is what the park uses for this kind of paper: this document,
     * another one (assignment.documentTemplateId says which), or null when it uses none. audit is the check of
     * this document against the deals in force at the park, when it uses this document.
     */
    public record ParkScreen(DocumentTemplateResponse document, ParkRef property,
                             PropertyDocumentAssignmentResponse assignment,
                             DocumentAuditResponse.FindingResponse audit,
                             List<TokenResponse> tokens,
                             History.PageHistory history) { }

    // ---- screens ---------------------------------------------------------------------------

    @PreAuthorize("hasAuthority('document_template:view')")
    @GetMapping("/documents")
    public ResponseEntity<DocumentsScreen> documents(Authentication authentication) {
        Set<String> can = authorities(authentication);
        PropertyScope scope = AgentPrincipal.scopeOf(authentication);
        boolean seesParks = can.contains("property_document:view");

        List<PropertyDocumentAssignment> assignments = seesParks
                ? assignmentService.findAll().stream().filter(a -> scope.allows(a.propertyId())).toList()
                : List.of();
        Map<UUID, Long> parksByDocument = assignments.stream()
                .filter(a -> a.document() != null)
                .collect(Collectors.groupingBy(a -> a.document().uuid(), Collectors.counting()));

        List<DocumentLine> documents = documentTemplateService.findAll(null, null).stream()
                .map(summary -> documentTemplateService.findById(summary.uuid()).orElse(summary))
                .map(doc -> lineOf(doc, seesParks ? parksByDocument.getOrDefault(doc.uuid(), 0L).intValue() : null))
                .toList();

        List<ParkLine> parks = null;
        if (seesParks) {
            Map<UUID, List<PropertyDocumentAssignment>> byPark = assignments.stream()
                    .collect(Collectors.groupingBy(PropertyDocumentAssignment::propertyId));
            parks = propertyService.findAll(scope).stream()
                    .sorted(Comparator.comparing(p -> p.propertyCode() == null ? "" : p.propertyCode()))
                    .map(p -> new ParkLine(p.uuid(), p.propertyCode(), p.propertyName(),
                            byPark.getOrDefault(p.uuid(), List.of()).stream()
                                    .map(a -> new ParkDocument(a.uuid(), a.document() == null ? null : a.document().uuid(),
                                            a.documentName(), a.agreementType(), a.instrumentType(),
                                            a.customizations().size()))
                                    .toList()))
                    .toList();
        }
        return ResponseEntity.ok(new DocumentsScreen(documents, parks));
    }

    // The document page. 404 for an unknown or deleted document.
    @PreAuthorize("hasAuthority('document_template:view')")
    @GetMapping("/document/{documentId}")
    public ResponseEntity<DocumentScreen> document(@PathVariable UUID documentId,
                                                   @RequestParam(required = false) String zone,
                                                   Authentication authentication) {
        Optional<DocumentTemplate> found = documentTemplateService.findById(documentId);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        DocumentTemplate doc = found.get();
        Set<String> can = authorities(authentication);
        PropertyScope scope = AgentPrincipal.scopeOf(authentication);

        List<PropertyDocumentAssignment> using = can.contains("property_document:view")
                ? assignmentService.findAll().stream()
                        .filter(a -> a.document() != null && a.document().uuid().equals(documentId))
                        .filter(a -> scope.allows(a.propertyId()))
                        .toList()
                : null;
        List<DocumentPark> parks = null;
        if (using != null) {
            Map<UUID, Property> properties = propertiesById(scope);
            parks = using.stream()
                    .filter(a -> properties.containsKey(a.propertyId()))
                    .map(a -> {
                        Property p = properties.get(a.propertyId());
                        return new DocumentPark(a.uuid(), p.uuid(), p.propertyCode(), p.propertyName(),
                                a.customizations().size(), a.note());
                    })
                    .sorted(Comparator.comparing(p -> p.propertyCode() == null ? "" : p.propertyCode()))
                    .toList();
        }

        History.PageHistory history = null;
        if (can.contains("audit:view")) {
            List<History.Ref> shown = new ArrayList<>();
            shown.add(new History.Ref("document_template", documentId));
            doc.sections().forEach(s -> shown.add(new History.Ref("document_section", s.uuid())));
            doc.styles().forEach(s -> shown.add(new History.Ref("document_style", s.uuid())));
            if (using != null) {
                using.forEach(a -> shown.add(new History.Ref("property_document_assignment", a.uuid())));
            }
            List<History.Ref> counted = doc.clausesInOrder().stream()
                    .map(c -> new History.Ref("template_clause", c.uuid()))
                    .toList();
            history = auditService.pageHistory(shown, counted, AuditService.zoneOf(zone));
        }
        return ResponseEntity.ok(new DocumentScreen(DocumentTemplateResponse.from(doc), parks, history));
    }

    // The section page. 404 for an unknown or deleted document, or a section not (or no longer) in it.
    @PreAuthorize("hasAuthority('document_template:view')")
    @GetMapping("/document/{documentId}/section/{sectionId}")
    public ResponseEntity<SectionScreen> section(@PathVariable UUID documentId,
                                                 @PathVariable UUID sectionId,
                                                 @RequestParam(required = false) String zone,
                                                 Authentication authentication) {
        Optional<DocumentTemplate> found = documentTemplateService.findById(documentId);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        DocumentTemplate doc = found.get();
        Optional<DocumentSection> section = doc.sections().stream().filter(s -> s.uuid().equals(sectionId)).findFirst();
        if (section.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        History.PageHistory history = null;
        if (authorities(authentication).contains("audit:view")) {
            List<History.Ref> shown = new ArrayList<>();
            shown.add(new History.Ref("document_section", sectionId));
            section.get().clauses().forEach(c -> shown.add(new History.Ref("template_clause", c.uuid())));
            history = auditService.pageHistory(shown, List.of(new History.Ref("document_template", documentId)),
                    AuditService.zoneOf(zone));
        }
        return ResponseEntity.ok(new SectionScreen(DocumentTemplateResponse.from(doc), sectionId,
                tokensFor(doc.instrumentType()), history));
    }

    // The document at one park. property_document:view. 404 for an unknown document, or a park that is unknown,
    // deleted or outside the agent's properties.
    @PreAuthorize("hasAuthority('document_template:view') and hasAuthority('property_document:view')")
    @GetMapping("/document/{documentId}/park/{propertyId}")
    public ResponseEntity<ParkScreen> park(@PathVariable UUID documentId,
                                           @PathVariable UUID propertyId,
                                           @RequestParam(required = false) String zone,
                                           Authentication authentication) {
        Optional<DocumentTemplate> found = documentTemplateService.findById(documentId);
        Optional<Property> property = propertyService.findByPropertyId(propertyId);
        if (found.isEmpty() || property.isEmpty() || property.get().deletedAt() != null
                || !AgentPrincipal.scopeOf(authentication).allows(propertyId)) {
            return ResponseEntity.notFound().build();
        }
        DocumentTemplate doc = found.get();
        Property park = property.get();

        Optional<PropertyDocumentAssignment> assignment = assignmentService.findForProperty(
                propertyId, doc.agreementType(), doc.instrumentType());
        boolean usesThis = assignment.isPresent() && assignment.get().document() != null
                && assignment.get().document().uuid().equals(documentId);

        DocumentAuditResponse.FindingResponse audit = null;
        if (usesThis) {
            UUID assignmentId = assignment.get().uuid();
            audit = DocumentAuditResponse.from(documentAuditService.auditProperty(propertyId)).documents().stream()
                    .filter(f -> assignmentId.equals(f.assignmentId()))
                    .findFirst()
                    .orElse(null);
        }

        History.PageHistory history = null;
        if (usesThis && authorities(authentication).contains("audit:view")) {
            List<History.Ref> shown = new ArrayList<>();
            shown.add(new History.Ref("property_document_assignment", assignment.get().uuid()));
            for (PropertyDocumentCustomization c : assignment.get().customizations()) {
                shown.add(new History.Ref("property_document_customization", c.uuid()));
            }
            history = auditService.pageHistory(shown, List.of(), AuditService.zoneOf(zone));
        }

        return ResponseEntity.ok(new ParkScreen(
                DocumentTemplateResponse.from(doc),
                new ParkRef(park.uuid(), park.propertyCode(), park.propertyName()),
                assignment.map(PropertyDocumentAssignmentResponse::from).orElse(null),
                audit,
                tokensFor(doc.instrumentType()),
                history));
    }

    // ---- internals -------------------------------------------------------------------------

    private static DocumentLine lineOf(DocumentTemplate doc, Integer parks) {
        int clauses = doc.clausesInOrder().size();
        int unfinished = (int) doc.conditionWorklist().stream()
                .filter(m -> m.clauseCount() > 0)
                .filter(m -> m.uncovered().stream().anyMatch(value -> !"NONE".equals(value)))
                .count();
        int warnings = (int) doc.clausesInOrder().stream()
                .filter(c -> !c.unguardedTokens().isEmpty())
                .count();
        return new DocumentLine(doc.uuid(), doc.name(), doc.agreementType(), doc.instrumentType(), doc.version(),
                doc.note(), doc.sections().size(), clauses, parks, unfinished, warnings, doc.createdAt());
    }

    // The tokens a clause of this kind of paper may use, as the token picker lists them
    private static List<TokenResponse> tokensFor(InstrumentType instrumentType) {
        return Arrays.stream(DocumentToken.values())
                .filter(token -> token.resolvesOn(instrumentType))
                .sorted(Comparator.comparing(DocumentToken::namespace).thenComparing(DocumentToken::token))
                .map(TokenResponse::from)
                .toList();
    }

    private Map<UUID, Property> propertiesById(PropertyScope scope) {
        Map<UUID, Property> out = new HashMap<>();
        propertyService.findAll(scope).forEach(p -> out.put(p.uuid(), p));
        return out;
    }

    // The permissions this request was checked with
    private static Set<String> authorities(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

}

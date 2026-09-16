package io.github.lordship.documenttemplate.internal;

import io.github.lordship.documenttemplate.DocumentTemplate;
import io.github.lordship.documenttemplate.DocumentTemplateService;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import io.github.lordship.shared.DocumentToken;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The global document pool: templates, their sections, and the clauses inside
 * them. Admin-only, and global by definition -- there are no property-scoped
 * rows here. A property picks a document up through
 * {@code property_document_assignment}, and an edit made on this controller
 * reaches every property that has, plus every future render. Documents already
 * generated are unaffected: their wording lives on the instrument.
 *
 * <p>Sections and clauses are edited through the template rather than getting
 * their own controllers, because neither means anything on its own -- the same
 * reason lot agreement types hang off {@code LotController}.
 *
 * <p>Creates take the bare minimum and nothing else. A section needs a name, a
 * clause needs nothing at all; ordinal is assigned at insert the way
 * {@code lot.sort_order} is, and everything optional arrives by PATCH. "Add
 * clause" is a button, not a form.
 */
@RestController
@RequestMapping("/api/document-templates")
public class DocumentTemplateController {

    // JSON field -> column.
    private static final Map<String, String> TEMPLATE_PATCHABLE = Map.ofEntries(
            Map.entry("name", "name"),
            Map.entry("note", "note"));

    private static final Map<String, String> SECTION_PATCHABLE = Map.ofEntries(
            Map.entry("ordinal", "ordinal"),
            Map.entry("name", "name"),
            Map.entry("sectionKey", "section_key"),
            Map.entry("signatureBlock", "signature_block"),
            Map.entry("listedAsAddendum", "listed_as_addendum"),
            Map.entry("required", "required"),
            Map.entry("statuteRef", "statute_ref"),
            Map.entry("note", "note"));

    private static final Map<String, String> CLAUSE_PATCHABLE = Map.ofEntries(
            Map.entry("ordinal", "ordinal"),
            Map.entry("clauseKey", "clause_key"),
            Map.entry("title", "title"),
            Map.entry("body", "body"),
            Map.entry("conditionField", "condition_field"),
            Map.entry("conditionValues", "condition_values"),
            Map.entry("required", "required"),
            Map.entry("statuteRef", "statute_ref"),
            Map.entry("note", "note"));

    private final DocumentTemplateService documentTemplateService;

    public DocumentTemplateController(DocumentTemplateService documentTemplateService) {
        this.documentTemplateService = documentTemplateService;
    }

    // All three are NOT NULL with no default, so all three are the minimum.
    public record CreateTemplateRequest(
            @NotBlank String name,
            @NotNull AgreementType agreementType,
            @NotNull InstrumentType instrumentType) { }

    // A section has to be called something. Everything else -- the signature
    // block, the addenda checkbox, whether a park may drop it -- is PATCH.
    public record CreateSectionRequest(@NotBlank String name) { }

    // ---- templates -----------------------------------------------------------

    @PreAuthorize("hasAuthority('document_template:view')")
    @GetMapping
    public ResponseEntity<List<DocumentTemplateResponse>> listTemplates(
            @RequestParam(value = "agreementType", required = false) AgreementType agreementType,
            @RequestParam(value = "instrumentType", required = false) InstrumentType instrumentType) {

        return ResponseEntity.ok(
                documentTemplateService.findAll(agreementType, instrumentType).stream()
                        .map(DocumentTemplateResponse::summary)
                        .toList());
    }

    /** The whole document: sections in order, each with its clauses in order. */
    @PreAuthorize("hasAuthority('document_template:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<DocumentTemplateResponse> getTemplate(@PathVariable UUID uuid) {
        return documentTemplateService.findById(uuid)
                .map(DocumentTemplateResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('document_template:create')")
    @PostMapping
    public ResponseEntity<DocumentTemplateResponse> createTemplate(
            @Valid @RequestBody CreateTemplateRequest request) {

        DocumentTemplate created = documentTemplateService.createTemplate(
                request.name(), request.agreementType(), request.instrumentType());

        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentTemplateResponse.from(created));
    }

    @PreAuthorize("hasAuthority('document_template:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<DocumentTemplateResponse> patchTemplate(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        return documentTemplateService.patchTemplate(uuid, columns(request, TEMPLATE_PATCHABLE))
                .map(DocumentTemplateResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // 409 when a property still has this document assigned -- retiring a
    // document out from under a park is how a lease stops being generatable
    // without anyone noticing.
    @PreAuthorize("hasAuthority('document_template:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable UUID uuid) {
        return documentTemplateService.deleteTemplate(uuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ---- sections ------------------------------------------------------------

    // Lands last in the packet. Reorder with PATCH -- ordinals are sparse, so
    // 12.5 sits between 12 and 13 and nothing else moves.
    @PreAuthorize("hasAuthority('document_template:edit')")
    @PostMapping("/{templateUuid}/sections")
    public ResponseEntity<DocumentTemplateResponse> createSection(
            @PathVariable UUID templateUuid,
            @Valid @RequestBody CreateSectionRequest request) {

        return documentTemplateService.createSection(templateUuid, request.name())
                .map(DocumentTemplateResponse::from)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('document_template:edit')")
    @PatchMapping("/sections/{sectionUuid}")
    public ResponseEntity<DocumentTemplateResponse> patchSection(
            @PathVariable UUID sectionUuid,
            @RequestBody Map<String, Object> request) {

        return documentTemplateService.patchSection(sectionUuid, columns(request, SECTION_PATCHABLE))
                .map(DocumentTemplateResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // 409 on a required section: it exists to satisfy a statute, and dropping
    // it globally is not the same act as one park excluding it.
    @PreAuthorize("hasAuthority('document_template:edit')")
    @DeleteMapping("/sections/{sectionUuid}")
    public ResponseEntity<Void> deleteSection(@PathVariable UUID sectionUuid) {
        return documentTemplateService.deleteSection(sectionUuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ---- clauses -------------------------------------------------------------

    // No body: an empty clause at the end of the section, which the editor then
    // fills in. Everything a clause carries is nullable in the schema.
    @PreAuthorize("hasAuthority('document_template:edit')")
    @PostMapping("/sections/{sectionUuid}/clauses")
    public ResponseEntity<DocumentTemplateResponse> createClause(@PathVariable UUID sectionUuid) {
        return documentTemplateService.createClause(sectionUuid)
                .map(DocumentTemplateResponse::from)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('document_template:edit')")
    @PatchMapping("/clauses/{clauseUuid}")
    public ResponseEntity<DocumentTemplateResponse> patchClause(
            @PathVariable UUID clauseUuid,
            @RequestBody Map<String, Object> request) {

        return documentTemplateService.patchClause(clauseUuid, columns(request, CLAUSE_PATCHABLE))
                .map(DocumentTemplateResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('document_template:edit')")
    @DeleteMapping("/clauses/{clauseUuid}")
    public ResponseEntity<Void> deleteClause(@PathVariable UUID clauseUuid) {
        return documentTemplateService.deleteClause(clauseUuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ---- preview -------------------------------------------------------------

    // Supply one or the other. methodValues is typed by hand and works on a
    // document assigned nowhere; chargeTerm reads a real deal.
    public record PreviewRequest(
            Map<String, String> methodValues,
            UUID chargeTerm) { }

    /**
     * The document as it would come out for one configuration -- which clauses
     * print, in order, and which were held back and why.
     *
     * <p>POST rather than GET because the input is a map of up to nine methods,
     * which is a body rather than a query string. It reads and changes nothing.
     *
     * <p>Bodies keep their tokens: this answers whether the document is complete
     * and reads correctly, which is a question about structure and prose.
     * Substituting figures needs a real tenancy and belongs to generate.
     */
    @PreAuthorize("hasAuthority('document_template:view')")
    @PostMapping("/{uuid}/preview")
    public ResponseEntity<DocumentTemplate.Preview> preview(
            @PathVariable UUID uuid,
            @RequestBody(required = false) PreviewRequest request) {

        if (request != null && request.chargeTerm() != null) {
            return documentTemplateService.previewForChargeTerm(uuid, request.chargeTerm())
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        }

        Map<String, String> methodValues = (request == null || request.methodValues() == null)
                ? Map.of()
                : request.methodValues();

        return documentTemplateService.preview(uuid, methodValues)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ---- the token picker ----------------------------------------------------

    /**
     * Feeds the clause editor. Read straight off the enum rather than a table,
     * so the list can never drift from what the renderer can actually resolve.
     *
     * <p>Pass the instrument type and the list narrows to tokens that have a
     * value on that kind of document -- a lease term on a rent increase notice
     * renders blank, and offering it is a trap the clause author cannot see.
     * Which tokens those are is {@code DocumentToken}'s business, not this
     * controller's.
     */
    @PreAuthorize("hasAuthority('document_template:view')")
    @GetMapping("/tokens")
    public ResponseEntity<List<TokenResponse>> listTokens(
            @RequestParam(value = "instrumentType", required = false) InstrumentType instrumentType) {

        List<TokenResponse> tokens = Arrays.stream(DocumentToken.values())
                .filter(token -> token.resolvesOn(instrumentType))
                .sorted(Comparator.comparing(DocumentToken::namespace)
                        .thenComparing(DocumentToken::token))
                .map(TokenResponse::from)
                .toList();

        return ResponseEntity.ok(tokens);
    }

    /**
     * What the clause editor's token picker shows. Grouped by namespace,
     * because seventy tokens in one alphabetical list is unusable.
     *
     * <p>{@code allowedValues} is populated only for tokens a clause may branch
     * on, so the condition editor can offer a checklist instead of a free-text
     * box someone can typo into.
     *
     * <p>{@code governedBy} and {@code populatedWhen} appear on amount tokens,
     * and are what let the editor propose the condition at the moment a token
     * is inserted -- "this only has a value when term.late_fee_method is FLAT;
     * condition the clause on that?" -- instead of leaving the author to work
     * out which of nine methods applies.
     */
    public record TokenResponse(
            String placeholder,
            String namespace,
            String source,
            String format,
            String description,
            boolean canCondition,
            List<String> allowedValues,
            String governedBy,
            List<String> populatedWhen) {

        static TokenResponse from(DocumentToken token) {
            return new TokenResponse(
                    token.placeholder(),
                    token.namespace(),
                    token.source().name(),
                    token.format().name(),
                    token.description(),
                    token.canCondition(),
                    token.allowedValues().stream().sorted().toList(),
                    token.governedBy().map(DocumentToken::token).orElse(null),
                    token.populatedWhen().stream().sorted().toList());
        }
    }

    // ---- internals -----------------------------------------------------------

    private static Map<String, Object> columns(Map<String, Object> request, Map<String, String> patchable) {
        Map<String, Object> changes = new HashMap<>();
        patchable.forEach((jsonField, column) -> {
            if (request.containsKey(jsonField)) {
                changes.put(column, request.get(jsonField));
            }
        });
        return changes;
    }

    // This controller returns the message, like TenancyChargeTermController and
    // unlike the older ones. "no such token {{raet}} -- did you mean {{rate}}?"
    // is the entire value of the refusal, and an empty 400 throws it away.
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
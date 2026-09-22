package io.github.lordship.instruments.internal;

import io.github.lordship.instruments.InstrumentService;
import io.github.lordship.shared.InstrumentType;
import org.springframework.context.MessageSource;
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
import io.github.lordship.documenttemplate.DocumentTemplate;
import io.github.lordship.documenttemplate.DocumentTemplateService;
import io.github.lordship.instruments.LeaseDocument;
import org.springframework.http.MediaType;


import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The paper for one tenancy.
 *
 * <p>Can only be written to when status = DRAFT
 * 409 error statuses on bad values
 */
@RestController
@RequestMapping("/api/instruments")
public class InstrumentController {

    private static final Map<String, String> PATCHABLE_COLUMNS = Map.ofEntries(
            Map.entry("termStart", "term_start"),
            Map.entry("termMonths", "term_months"),
            Map.entry("onExpiry", "on_expiry"),
            Map.entry("amends", "amends"),
            Map.entry("note", "note"));

    private static final Map<String, String> CLAUSE_COLUMNS = Map.ofEntries(
            Map.entry("ordinal", "ordinal"),
            Map.entry("title", "title"),
            Map.entry("body", "body"),
            Map.entry("note", "note"));

    private final InstrumentService instrumentService;

    // The same MessageSource the global exception handler uses, so a missing
    // value and a refusal are worded out of one properties file.
    private final MessageSource messages;

    private final DocumentTemplateService documentTemplateService;

    public InstrumentController(InstrumentService instrumentService,
                                MessageSource messages,
                                DocumentTemplateService documentTemplateService) {
        this.instrumentService = instrumentService;
        this.messages = messages;
        this.documentTemplateService = documentTemplateService;
    }

    /**
     * {@code batchId} is the deal this paper is being written for -- the batch
     * the charge terms were drafted under. Optional, because a notice carries
     * no term of its own; supplied, every step of the deal points at this
     * document from the moment it exists.
     */
    public record CreateDraftRequest(
            @NotNull UUID tenancyId,
            @NotNull InstrumentType type,
            UUID batchId) { }

    public record AddClauseRequest(@NotNull UUID sectionId) { }

    // ---- reading -------------------------------------------------------------

    /** The paper history for one tenancy, or just what is out in the field. */
    @PreAuthorize("hasAuthority('instrument:view')")
    @GetMapping
    public ResponseEntity<List<InstrumentResponse>> listByTenancy(
            @RequestParam("tenancy") UUID tenancyId,
            @RequestParam(value = "open", required = false) Boolean openOnly) {

        List<InstrumentResponse> found = (Boolean.TRUE.equals(openOnly)
                ? instrumentService.findOpenByTenancy(tenancyId)
                : instrumentService.findByTenancy(tenancyId))
                .stream().map(InstrumentResponse::from).toList();

        return ResponseEntity.ok(found);
    }

    /**
     * Type the number off the paper, get the document. Case, spacing and the
     * characters people misread are all normalized first, so the office worker
     * is never told a serial does not exist because they typed O for zero.
     */
    @PreAuthorize("hasAuthority('instrument:view')")
    @GetMapping("/by-serial")
    public ResponseEntity<InstrumentResponse> getBySerial(@RequestParam("serial") String serial) {
        return instrumentService.findBySerial(serial)
                .map(InstrumentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('instrument:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<InstrumentResponse> getInstrument(@PathVariable UUID uuid) {
        return instrumentService.findById(uuid)
                .map(InstrumentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** The clauses somebody typed onto this document, for the review screen. */
    @PreAuthorize("hasAuthority('instrument:view')")
    @GetMapping("/{uuid}/clauses")
    public ResponseEntity<List<InstrumentAdditionResponse>> listClauses(@PathVariable UUID uuid) {
        return ResponseEntity.ok(instrumentService.findAdditions(uuid).stream()
                .map(InstrumentAdditionResponse::from)
                .toList());
    }

    /**
     * Lease preview using a tenant's term info
     * <p>Always 200 when the instrument exists
     * {@code complete} says whether Generate may be pressed, and the two
     * problem lists say what to fix.
     * 409 when there is no deal attached or the park has no document
     *  for this kind of agreement
     */
    @PreAuthorize("hasAuthority('instrument:view')")
    @GetMapping("/{uuid}/preview")
    public ResponseEntity<LeasePreviewResponse> preview(@PathVariable UUID uuid) {
        return instrumentService.preview(uuid)
                .map(preview -> LeasePreviewResponse.from(preview, messages))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }


    @PreAuthorize("hasAuthority('instrument:view')")
    @GetMapping(value = "/{uuid}/preview.html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> previewHtml(@PathVariable UUID uuid) {
        return instrumentService.preview(uuid)
                .map(preview -> LeaseDocument.render(
                        preview,
                        "PREVIEW",
                        documentTemplateService.findById(preview.documentTemplate())
                                .map(DocumentTemplate::styles).orElse(List.of())))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ---- the draft -----------------------------------------------------------

    @PreAuthorize("hasAuthority('instrument:create')")
    @PostMapping
    public ResponseEntity<InstrumentResponse> createDraft(
            @Valid @RequestBody CreateDraftRequest request) {

        return instrumentService.createDraft(
                        request.tenancyId(), request.type(), request.batchId())
                .map(InstrumentResponse::from)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
                .orElse(ResponseEntity.notFound().build());
    }

    /** The period this document covers. 409 once it has left DRAFT. */
    @PreAuthorize("hasAuthority('instrument:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<InstrumentResponse> patchDraft(
            @PathVariable UUID uuid,
            @RequestBody Map<String, Object> request) {

        return instrumentService.patchDraft(uuid, columns(request, PATCHABLE_COLUMNS))
                .map(InstrumentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Not a DELETE: the document is not removed, it is recorded as paper that
    // never went out. 409 when it is too far along for that to be true.
    @PreAuthorize("hasAuthority('instrument:edit')")
    @PostMapping("/{uuid}/abandon")
    public ResponseEntity<InstrumentResponse> abandon(@PathVariable UUID uuid) {
        return instrumentService.abandon(uuid)
                .map(InstrumentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ---- what the office worker typed ----------------------------------------

    @PreAuthorize("hasAuthority('instrument:edit')")
    @PostMapping("/{uuid}/clauses")
    public ResponseEntity<InstrumentAdditionResponse> addClause(
            @PathVariable UUID uuid,
            @Valid @RequestBody AddClauseRequest request) {

        return instrumentService.addClause(uuid, request.sectionId())
                .map(InstrumentAdditionResponse::from)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
                .orElse(ResponseEntity.notFound().build());
    }

    /** 400 when the wording names a token that does not exist. */
    @PreAuthorize("hasAuthority('instrument:edit')")
    @PatchMapping("/clauses/{additionUuid}")
    public ResponseEntity<InstrumentAdditionResponse> patchClause(
            @PathVariable UUID additionUuid,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> changes = columns(request, CLAUSE_COLUMNS);
        if (changes.containsKey("ordinal")) {
            changes.put("ordinal", asOrdinal(changes.get("ordinal")));
        }

        return instrumentService.patchClause(additionUuid, changes)
                .map(InstrumentAdditionResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('instrument:edit')")
    @DeleteMapping("/clauses/{additionUuid}")
    public ResponseEntity<Void> removeClause(@PathVariable UUID additionUuid) {
        return instrumentService.removeClause(additionUuid)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ---- internals -----------------------------------------------------------

    private static Map<String, Object> columns(Map<String, Object> request, Map<String, String> allowed) {
        Map<String, Object> changes = new HashMap<>();
        allowed.forEach((jsonField, column) -> {
            if (request.containsKey(jsonField)) {
                changes.put(column, request.get(jsonField));
            }
        });
        return changes;
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

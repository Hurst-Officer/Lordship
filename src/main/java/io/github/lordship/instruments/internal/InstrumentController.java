package io.github.lordship.instruments.internal;

import io.github.lordship.documentfiles.StoredFile;
import io.github.lordship.instruments.InstrumentService;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import io.github.lordship.tenancyterms.RentStep;
import io.github.lordship.tenancyterms.TenancyChargeTerm;
import io.github.lordship.tenancyterms.TenancyChargeTermResponse;
import org.springframework.context.MessageSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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

    // agreementType picks the document template and the terms template.
    public record CreateDraftRequest(
            @NotNull UUID tenancyId,
            @NotNull InstrumentType type,
            @NotNull AgreementType agreementType) { }

    // The rent steps the office worker confirmed, saved exactly as given.
    public record ScheduleRequest(@NotEmpty List<RentStep> steps) { }

    public record CancelFutureRequest(@NotBlank String cancelReason) { }

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
                        request.tenancyId(), request.type(), request.agreementType())
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

    // ---- generate ------------------------------------------------------------

    // Freezes the wording, stamps the serial, saves the PDF and moves the charge
    // terms to PENDING. 400 lists what is missing. 409 if it is not a draft.
    @PreAuthorize("hasAuthority('instrument:edit')")
    @PostMapping("/{uuid}/generate")
    public ResponseEntity<InstrumentResponse> generate(@PathVariable UUID uuid) {
        return instrumentService.generate(uuid)
                .map(InstrumentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // The generated PDF. 404 if the document does not exist or was not generated yet.
    @PreAuthorize("hasAuthority('instrument:view')")
    @GetMapping("/{uuid}/file")
    public ResponseEntity<byte[]> downloadFile(@PathVariable UUID uuid) {
        return instrumentService.readGeneratedFile(uuid)
                .map(InstrumentController::fileResponse)
                .orElse(ResponseEntity.notFound().build());
    }

    // ---- charge terms --------------------------------------------------------
    // The charge terms of a document are created here, already linked to it.
    // Fees are then edited with PATCH /api/tenancy-charge-terms/{uuid}.

    @PreAuthorize("hasAuthority('tenancy_term:view')")
    @GetMapping("/{uuid}/charge-terms")
    public ResponseEntity<List<TenancyChargeTermResponse>> listChargeTerms(@PathVariable UUID uuid) {
        return instrumentService.findChargeTerms(uuid)
                .map(InstrumentController::toTermResponses)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // What the office worker sees before confirming. Nothing is saved.
    // 400 if the document's start date or number of months is not set.
    @PreAuthorize("hasAuthority('tenancy_term:create')")
    @GetMapping("/{uuid}/charge-terms/schedule")
    public ResponseEntity<List<RentStep>> previewSchedule(@PathVariable UUID uuid) {
        return instrumentService.previewSchedule(uuid)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Saves the confirmed steps as charge terms linked to this document.
    // Posting again replaces them and keeps the fees already set.
    // 409 once the document has left DRAFT.
    @PreAuthorize("hasAuthority('tenancy_term:create')")
    @PostMapping("/{uuid}/charge-terms/schedule")
    public ResponseEntity<List<TenancyChargeTermResponse>> writeSchedule(
            @PathVariable UUID uuid,
            @Valid @RequestBody ScheduleRequest request) {

        return instrumentService.writeSchedule(uuid, request.steps())
                .map(InstrumentController::toTermResponses)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
                .orElse(ResponseEntity.notFound().build());
    }

    // Cancels the steps that have not taken effect yet. Used when a lease ends early.
    @PreAuthorize("hasAuthority('tenancy_term:cancel')")
    @PostMapping("/{uuid}/charge-terms/cancel-future")
    public ResponseEntity<List<TenancyChargeTermResponse>> cancelFutureChargeTerms(
            @PathVariable UUID uuid,
            @Valid @RequestBody CancelFutureRequest request) {

        return instrumentService.cancelFutureChargeTerms(uuid, request.cancelReason())
                .map(InstrumentController::toTermResponses)
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

    // "inline" so a browser opens the PDF instead of only saving it.
    private static ResponseEntity<byte[]> fileResponse(StoredFile stored) {
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(stored.file().fileName())
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.file().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(stored.content());
    }

    private static List<TenancyChargeTermResponse> toTermResponses(List<TenancyChargeTerm> terms) {
        return terms.stream().map(TenancyChargeTermResponse::from).toList();
    }

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

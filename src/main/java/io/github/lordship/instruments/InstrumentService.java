package io.github.lordship.instruments;

import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.documenttemplate.DocumentSection;
import io.github.lordship.documenttemplate.PropertyDocumentAssignment;
import io.github.lordship.documenttemplate.PropertyDocumentAssignmentService;
import io.github.lordship.globalsettings.GlobalSettingsService;
import io.github.lordship.instruments.internal.InstrumentAdditionRepository;
import io.github.lordship.instruments.internal.InstrumentAdditionRow;
import io.github.lordship.instruments.internal.InstrumentClauseRepository;
import io.github.lordship.instruments.internal.InstrumentClauseRow;
import io.github.lordship.instruments.internal.InstrumentRepository;
import io.github.lordship.instruments.internal.InstrumentRow;
import io.github.lordship.instruments.internal.InstrumentSectionRepository;
import io.github.lordship.instruments.internal.InstrumentSectionRow;
import io.github.lordship.documentfiles.DocumentFile;
import io.github.lordship.documentfiles.DocumentFileService;
import io.github.lordship.documentfiles.StoredFile;
import io.github.lordship.lots.Lot;
import io.github.lordship.lots.LotService;
import io.github.lordship.persons.Person;
import io.github.lordship.persons.PersonService;
import io.github.lordship.properties.Property;
import io.github.lordship.properties.PropertyService;
import io.github.lordship.shared.ClauseBodyRules;
import io.github.lordship.shared.DomainProblem;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancy.TenancyService;
import io.github.lordship.tenancyterms.RentHistoryYear;
import io.github.lordship.tenancyterms.RentStep;
import io.github.lordship.tenancyterms.TenancyChargeTerm;
import io.github.lordship.tenancyterms.TenancyChargeTermService;
import io.github.lordship.tenancyterms.TenancyTermSource;
import io.github.lordship.tenants.TenantService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One piece of paper, from the moment somebody decides to write it.
 *
 * <p>A document is drafted, generated, sent, served and accepted, and only the
 * draft may be changed. After that a correction is a new instrument, because
 * the copy a tenant is holding cannot be edited retroactively and pretending
 * otherwise is how a file stops matching the paper in somebody's kitchen drawer.
 *
 * <p>This class covers the draft (creating it, its dates, its charge terms and
 * typed clauses) and generate, which freezes the wording, stamps the serial,
 * saves the PDF and moves the document out of DRAFT for good.
 */
@Service
public class InstrumentService {

    private final InstrumentRepository instrumentRepository;
    private final InstrumentAdditionRepository additionRepository;
    private final TenancyService tenancyService;
    private final TenancyChargeTermService chargeTermService;
    private final LotService lotService;
    private final PropertyService propertyService;
    private final TenantService tenantService;
    private final PersonService personService;
    private final PropertyDocumentAssignmentService assignmentService;
    private final GlobalSettingsService settingsService;
    private final AuditService auditService;
    private final AuditContext auditContext;
    private final InstrumentSectionRepository sectionRepository;
    private final InstrumentClauseRepository clauseRepository;
    private final DocumentFileService documentFileService;
    private final String serialPrefix;

    public InstrumentService(InstrumentRepository instrumentRepository,
                             InstrumentAdditionRepository additionRepository,
                             TenancyService tenancyService,
                             TenancyChargeTermService chargeTermService,
                             LotService lotService,
                             PropertyService propertyService,
                             TenantService tenantService,
                             PersonService personService,
                             PropertyDocumentAssignmentService assignmentService,
                             GlobalSettingsService settingsService,
                             AuditService auditService,
                             AuditContext auditContext,
                             InstrumentSectionRepository sectionRepository,
                             InstrumentClauseRepository clauseRepository,
                             DocumentFileService documentFileService,
                             @Value("${lordship.serial.prefix}") String serialPrefix) {
        this.instrumentRepository = instrumentRepository;
        this.additionRepository = additionRepository;
        this.tenancyService = tenancyService;
        this.chargeTermService = chargeTermService;
        this.lotService = lotService;
        this.propertyService = propertyService;
        this.tenantService = tenantService;
        this.personService = personService;
        this.assignmentService = assignmentService;
        this.settingsService = settingsService;
        this.auditService = auditService;
        this.auditContext = auditContext;
        this.sectionRepository = sectionRepository;
        this.clauseRepository = clauseRepository;
        this.documentFileService = documentFileService;
        this.serialPrefix = serialPrefix;
    }

    // ---- reading -------------------------------------------------------------

    public Optional<Instrument> findById(UUID uuid) {
        return instrumentRepository.findById(uuid).map(InstrumentRow::toInstrument);
    }

    /**
     * Find a document by the number printed on it. Whatever was typed is put
     * through {@link Serial#normalize} first, so an O read as a zero, a missing
     * hyphen or lower case all still land on the right lease.
     */
    public Optional<Instrument> findBySerial(String typed) {
        String serial = Serial.normalize(typed);
        if (serial == null) {
            return Optional.empty();
        }
        return instrumentRepository.findBySerial(serial).map(InstrumentRow::toInstrument);
    }

    /** The paper history for one tenancy, newest first. */
    public List<Instrument> findByTenancy(UUID tenancy) {
        return instrumentRepository.findByTenancy(tenancy).stream()
                .map(InstrumentRow::toInstrument)
                .toList();
    }

    /** What is out in the field for this tenancy right now: the office work queue. */
    public List<Instrument> findOpenByTenancy(UUID tenancy) {
        return instrumentRepository.findOpenByTenancy(tenancy).stream()
                .map(InstrumentRow::toInstrument)
                .toList();
    }

    public List<InstrumentAddition> findAdditions(UUID instrument) {
        return additionRepository.findByInstrument(instrument).stream()
                .map(InstrumentAdditionRow::toInstrumentAddition)
                .toList();
    }

    // ---- the draft -----------------------------------------------------------

    /**
     * Starts a draft document. Returns empty if the tenancy does not exist.
     *
     * <p>agreementType is chosen now because it decides which document template
     * is used and which terms template the charge terms are copied from.
     *
     * <p>A LEASE gets its start date filled in (see {@link #defaultLeaseStart}).
     * The office worker can change it with a PATCH. Other document types start
     * with no dates.
     *
     * <p>No charge terms, template, wording or serial yet. The charge terms are
     * added with {@link #writeSchedule}. The template, wording and serial are
     * picked at generate, so a draft made today still prints a clause corrected
     * tomorrow.
     */
    @Transactional
    public Optional<Instrument> createDraft(UUID tenancy, InstrumentType type, AgreementType agreementType) {
        Optional<Tenancy> tenancyOpt = tenancyService.findTenancyById(tenancy);
        if (tenancyOpt.isEmpty()) {
            return Optional.empty();
        }

        LocalDate termStart = (type == InstrumentType.LEASE)
                ? defaultLeaseStart(tenancyOpt.get())
                : null;

        InstrumentRow saved = instrumentRepository.save(
                tenancy, type, agreementType, termStart, ActingAgent.resolve(auditContext));
        auditService.recordInsert("instrument", saved.uuid(), AuditMapper.toMap(saved));
        return Optional.of(saved.toInstrument());
    }

    /**
     * The period this document covers, and what it claims happens at the end.
     *
     * <p>{@code termStart} and {@code termMonths} belong to the paper rather
     * than the deal: a terms template says how often rent escalates, never for
     * how long. Refused once the document has left DRAFT.
     */
    @Transactional
    public Optional<Instrument> patchDraft(UUID uuid, Map<String, Object> changes) {
        Optional<InstrumentRow> beforeOpt = instrumentRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        InstrumentRow before = requireDraft(beforeOpt.get());

        Optional<InstrumentRow> afterOpt = instrumentRepository.patch(uuid, coerce(changes));
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }

        AuditMapper.Diff diff = AuditMapper.diff(before, afterOpt.get());
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("instrument", uuid, diff.before(), diff.after());
        }
        return Optional.of(afterOpt.get().toInstrument());
    }

    /**
     * Paper that never went out, or was replaced before it did.
     *
     * <p>Empty means no such instrument, or one too far along to abandon: a
     * document the tenant already has cannot be un-sent, and the answer for one
     * that is wrong is a new instrument saying so.
     */
    @Transactional
    public Optional<Instrument> abandon(UUID uuid) {
        Optional<InstrumentRow> beforeOpt = instrumentRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }

        Optional<InstrumentRow> afterOpt = instrumentRepository.abandon(uuid);
        if (afterOpt.isEmpty()) {
            throw RuleConflict.of("instrument.cannot_be_abandoned", beforeOpt.get().status());
        }

        AuditMapper.Diff diff = AuditMapper.diff(beforeOpt.get(), afterOpt.get());
        auditService.recordUpdate("instrument", uuid, diff.before(), diff.after());

        // The document's charge terms never went into force, so they go with it.
        chargeTermService.deleteForDocument(uuid);

        return Optional.of(afterOpt.get().toInstrument());
    }

    // ---- generate ------------------------------------------------------------

    /**
     * Turns a finished draft into the document that goes to the tenant.
     *
     * <p>In order:
     *   1. Check it is a DRAFT (409 if not) and, for a lease, has its dates (400).
     *   2. Assemble it exactly as preview does, and refuse if anything is missing (400, with the list).
     *   3. Move its charge terms to PENDING, so the deal cannot change under a printed document.
     *   4. Save every section and clause as printed.
     *   5. Stamp a serial, render the PDF and save it.
     *   6. Mark the document GENERATED.
     *
     * <p>All in one transaction. A refusal at any step changes nothing.
     * The database only moves a DRAFT to GENERATED once, so two clicks make one document.
     *
     * <p>Returns empty if there is no such document.
     */
    @Transactional
    public Optional<Instrument> generate(UUID uuid) {
        Optional<InstrumentRow> rowOpt = instrumentRepository.findById(uuid);
        if (rowOpt.isEmpty()) {
            return Optional.empty();
        }
        InstrumentRow before = requireDraft(rowOpt.get());
        requireTermDates(before);

        Assembly assembly = assemble(before);
        LeasePreview preview = assembly.preview();
        requireComplete(preview.frozen());

        chargeTermService.submitForDocument(before.uuid());
        saveFrozen(before.uuid(), preview.frozen());

        String serial = Serial.generate(serialPrefix, before.agreementType(), before.type());
        String html = LeaseDocument.render(preview, serial, assembly.assignment().document().styles());
        byte[] pdf = PdfRenderer.toPdf(html);

        DocumentFile file = documentFileService.store(
                serial + ".pdf",
                serial + ".pdf",
                "application/pdf",
                pdf,
                ActingAgent.resolve(auditContext));

        InstrumentRow after = instrumentRepository.markGenerated(
                        before.uuid(),
                        serial,
                        preview.documentTemplate(),
                        preview.documentVersion(),
                        assembly.assignment().uuid(),
                        file.uuid())
                // Somebody else generated it between our read and this write.
                .orElseThrow(() -> RuleConflict.of("instrument.not_editable", InstrumentStatus.GENERATED));

        AuditMapper.Diff diff = AuditMapper.diff(before, after);
        auditService.recordUpdate("instrument", uuid, diff.before(), diff.after());
        return Optional.of(after.toInstrument());
    }

    /** The generated PDF. Empty if there is no such document or it has not been generated. */
    public Optional<StoredFile> readGeneratedFile(UUID uuid) {
        return instrumentRepository.findById(uuid)
                .map(InstrumentRow::generatedFile)
                .flatMap(documentFileService::read);
    }

    // ---- charge terms --------------------------------------------------------

    /** The charge terms written for this document, earliest first. Empty if no such document. */
    public Optional<List<TenancyChargeTerm>> findChargeTerms(UUID uuid) {
        return instrumentRepository.findById(uuid)
                .map(row -> chargeTermService.findBySource(row.uuid()));
    }

    /**
     * The rent schedule the office worker sees before confirming it. Worked out
     * from the lot's rate, the terms template, and this document's start date
     * and number of months. Nothing is saved.
     *
     * <p>Returns empty if the document does not exist. Throws a 400 if the start
     * date or number of months is not set yet. A notice has no months, so for a
     * notice skip the preview and confirm its single step directly.
     */
    public Optional<List<RentStep>> previewSchedule(UUID uuid) {
        Optional<InstrumentRow> rowOpt = instrumentRepository.findById(uuid);
        if (rowOpt.isEmpty()) {
            return Optional.empty();
        }
        InstrumentRow row = rowOpt.get();
        TenancyTermSource source = chargeTermSourceFor(row);

        if (row.termStart() == null) {
            throw InvalidRequest.onField("termStart", "instrument.term_start_needed");
        }
        if (row.termMonths() == null) {
            throw InvalidRequest.onField("termMonths", "instrument.term_months_needed");
        }
        return chargeTermService.previewSchedule(
                row.tenancy(), row.agreementType(), row.termStart(), row.termMonths(), source);
    }

    /**
     * Saves the rent schedule the office worker confirmed. Each step becomes a
     * PROPOSED charge term linked to this document.
     *
     * <p>If the document already has charge terms, they are replaced and the
     * fees already set on them are kept (the "Rebuild schedule" button).
     *
     * <p>Only allowed while the document is a DRAFT. Returns empty if the
     * document does not exist.
     */
    @Transactional
    public Optional<List<TenancyChargeTerm>> writeSchedule(UUID uuid, List<RentStep> steps) {
        Optional<InstrumentRow> rowOpt = instrumentRepository.findById(uuid);
        if (rowOpt.isEmpty()) {
            return Optional.empty();
        }
        InstrumentRow row = requireDraft(rowOpt.get());
        TenancyTermSource source = chargeTermSourceFor(row);

        return chargeTermService.createForDocument(
                row.tenancy(), row.agreementType(), steps, source, row.uuid());
    }

    /**
     * Cancels this document's charge terms that have not taken effect yet.
     * Used when a lease ends early. Returns empty if the document does not exist.
     */
    @Transactional
    public Optional<List<TenancyChargeTerm>> cancelFutureChargeTerms(UUID uuid, String cancelReason) {
        return instrumentRepository.findById(uuid)
                .map(row -> chargeTermService.cancelFutureForDocument(row.uuid(), cancelReason));
    }

    // ---- what the office worker typed ----------------------------------------

    /**
     * A clause on this one agreement and no other. The shed the seller left,
     * which the tenant may keep until June.
     *
     * <p>Added empty and filled in by a patch, the same gesture as adding a
     * template clause or a park clause.
     *
     * <p>There is no matching removal of a clause the document already carries.
     * An assistant may add a sentence to a lease; taking a mandated disclosure
     * out of one is not something anybody gets to do here.
     */
    @Transactional
    public Optional<InstrumentAddition> addClause(UUID instrumentUuid, UUID sectionUuid) {
        Optional<InstrumentRow> instrument = instrumentRepository.findById(instrumentUuid);
        if (instrument.isEmpty()) {
            return Optional.empty();
        }
        requireDraft(instrument.get());

        InstrumentAdditionRow saved = additionRepository.save(
                instrumentUuid, sectionUuid, ActingAgent.resolve(auditContext));
        auditService.recordInsert("instrument_addition", saved.uuid(), AuditMapper.toMap(saved));
        return Optional.of(saved.toInstrumentAddition());
    }

    /**
     * The wording, title and position of a typed clause.
     *
     * <p>Held to the same rules a template clause is. Nothing typed by hand gets
     * to skip the token check: {@code {{term.rate}}} resolves here exactly as it
     * does anywhere else, and a token that does not exist is refused now rather
     * than standing in the text of a lease.
     */
    @Transactional
    public Optional<InstrumentAddition> patchClause(UUID additionUuid, Map<String, Object> changes) {
        Optional<InstrumentAdditionRow> beforeOpt = additionRepository.findById(additionUuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        InstrumentAdditionRow before = beforeOpt.get();
        requireDraftFor(before.instrument());

        ClauseBodyRules.validateBody(changes);

        Optional<InstrumentAdditionRow> afterOpt = additionRepository.patch(additionUuid, changes);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }

        AuditMapper.Diff diff = AuditMapper.diff(before, afterOpt.get());
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("instrument_addition", additionUuid, diff.before(), diff.after());
        }
        return Optional.of(afterOpt.get().toInstrumentAddition());
    }

    /** Takes back a sentence before the document goes out. */
    @Transactional
    public boolean removeClause(UUID additionUuid) {
        return additionRepository.findById(additionUuid).map(existing -> {
            requireDraftFor(existing.instrument());
            if (!additionRepository.softDelete(additionUuid)) {
                return false;
            }
            auditService.recordDelete(
                    "instrument_addition", additionUuid, AuditMapper.toMap(existing));
            return true;
        }).orElse(false);
    }

    // ---- the document as it would come out -----------------------------------

    /**
     * The lease with this tenant's real figures in it, without saving anything.
     *
     * <p>Empty means no such instrument. A deal or a document that is missing
     * is a conflict rather than a not-found: the records exist and the state
     * forbids the answer.
     */
    public Optional<LeasePreview> preview(UUID instrumentUuid) {
        return instrumentRepository.findById(instrumentUuid).map(row -> assemble(row).preview());
    }

    /**
     * Gather everything the document says, choose the clauses, and render them.
     *
     * <p>The whole of generate except the saving. Both call this, so a preview
     * that looks complete cannot be refused at generate and a preview that
     * looks wrong cannot quietly come out right -- two assemblies would
     * eventually disagree, and the once they did would be on a signed lease.
     */
    private Assembly assemble(InstrumentRow row) {
        Tenancy tenancy = tenancyService.findTenancyById(row.tenancy())
                .orElseThrow(() -> missing("tenancy", row.tenancy()));
        Lot lot = lotService.findById(tenancy.lotId())
                .orElseThrow(() -> missing("lot", tenancy.lotId()));
        Property property = propertyService.findByPropertyId(lot.propertyId())
                .orElseThrow(() -> missing("property", lot.propertyId()));

        List<TenancyChargeTerm> schedule = chargeTermService.findBySource(row.uuid());
        if (schedule.isEmpty()) {
            throw RuleConflict.of("instrument.no_term");
        }

        PropertyDocumentAssignment assignment = assignmentService
                .findForGenerate(property.uuid(), row.agreementType(), row.type())
                .orElseThrow(() -> RuleConflict.of("property.no_document",
                        row.type(), row.agreementType()));

        TokenResolver.LeaseFacts facts = new TokenResolver.LeaseFacts(
                row.toInstrument(),
                schedule.get(0),
                schedule,
                tenancy,
                lot,
                property,
                settingsService.require(),
                tenantNames(tenancy.uuid()),
                rentHistory(lot.uuid(), row.termStart()));

        List<DocumentSection> sections = assignment.document().sectionsInOrder();
        DocumentFreeze.Frozen frozen = DocumentFreeze.freeze(
                sections,
                assignment.customizations(),
                findAdditions(row.uuid()),
                TokenResolver.resolve(facts));

        LeasePreview preview = new LeasePreview(
                row.uuid(),
                assignment.document().uuid(),
                assignment.document().name(),
                assignment.document().version(),
                frozen);
        return new Assembly(preview, assignment);
    }

    /** What assemble produced, plus the park's document assignment that generate records. */
    private record Assembly(LeasePreview preview, PropertyDocumentAssignment assignment) {}

    /**
     * Who signs, in the order the tenancy holds them.
     *
     * <p>A tenant with no person behind it is skipped rather than printed as a
     * blank line -- and because {@code tenancy.tenant_names} is a required
     * token, a tenancy with nobody on it leaves it unset and generation
     * refuses. A lease naming no tenant is not a lease.
     */
    private List<String> tenantNames(UUID tenancy) {
        return tenantService.findActiveByTenancy(tenancy).stream()
                .map(tenant -> personService.findByID(tenant.personId()))
                .flatMap(Optional::stream)
                .map(Person::nameFull)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    /**
     * The five years the disclosure covers. Fetched for the window the resolver
     * will place them in, so the two cannot disagree about which years those
     * are.
     */
    private List<RentHistoryYear> rentHistory(UUID lot, LocalDate termStart) {
        List<Integer> years = RentHistory.disclosedYears(termStart);
        if (years.isEmpty()) {
            return List.of();
        }
        return chargeTermService.findRentHistoryByLot(
                lot, years.get(0), years.get(years.size() - 1));
    }

    private static EntityNotFoundException missing(String what, UUID uuid) {
        return new EntityNotFoundException(
                "Instrument points at a " + what + " that does not exist: " + uuid);
    }

    // ---- internals -----------------------------------------------------------

    /**
     * A lease (also an assumption or waiver) must have its start date and number
     * of months before it is generated. The database refuses it otherwise
     * (instrument_lease_has_term); this names the field instead.
     */
    private static void requireTermDates(InstrumentRow row) {
        if (!row.toInstrument().carriesTerm()) {
            return;
        }
        if (row.termStart() == null) {
            throw InvalidRequest.onField("termStart", "instrument.term_start_needed");
        }
        if (row.termMonths() == null) {
            throw InvalidRequest.onField("termMonths", "instrument.term_months_needed");
        }
    }

    /** Throws a 400 listing everything the preview says is missing. */
    private static void requireComplete(DocumentFreeze.Frozen frozen) {
        List<DomainProblem.Problem> problems = new ArrayList<>();
        for (String token : frozen.unresolved()) {
            problems.add(DomainProblem.Problem.of("instrument.unresolved_token", token));
        }
        for (String section : frozen.omittedRequired()) {
            problems.add(DomainProblem.Problem.of("instrument.required_section_empty", section));
        }
        for (String reference : frozen.brokenReferences()) {
            problems.add(DomainProblem.Problem.of("instrument.broken_reference", reference));
        }
        for (String pair : frozen.separatedPairs()) {
            problems.add(DomainProblem.Problem.of("instrument.separated_pair", pair));
        }
        if (!problems.isEmpty()) {
            throw InvalidRequest.withDetails("instrument.not_complete", problems);
        }
    }

    /**
     * Saves every section and clause exactly as printed. Anything saved by an
     * earlier attempt on this draft is removed first.
     */
    private void saveFrozen(UUID instrument, DocumentFreeze.Frozen frozen) {
        clauseRepository.deleteByInstrument(instrument);
        sectionRepository.deleteByInstrument(instrument);

        for (DocumentFreeze.FrozenSection section : frozen.sections()) {
            InstrumentSectionRow savedSection =
                    sectionRepository.save(InstrumentSectionRow.from(instrument, section));
            for (DocumentFreeze.FrozenClause clause : section.clauses()) {
                clauseRepository.save(
                        InstrumentClauseRow.from(instrument, savedSection.uuid(), clause, clause.origin()));
            }
        }
    }

    /**
     * Where a new lease starts unless the office worker changes it.
     *
     * <p>If the tenancy already has a lease, the new one starts the day after
     * the latest one ends. A new lease must never cut the current lease short.
     * Otherwise the new lease starts on the tenancy's start date.
     *
     * <p>Abandoned leases are skipped, and so are leases with no dates yet.
     * Returns null if there is nothing to go on.
     */
    private LocalDate defaultLeaseStart(Tenancy tenancy) {
        return instrumentRepository.findByTenancy(tenancy.uuid()).stream()
                .map(InstrumentRow::toInstrument)
                .filter(doc -> doc.type() == InstrumentType.LEASE)
                .filter(doc -> doc.status() != InstrumentStatus.ABANDONED)
                .map(Instrument::termEnd)
                .flatMap(Optional::stream)
                .max(Comparator.naturalOrder())
                .orElse(tenancy.startDate());
    }

    /**
     * The kind of charge term this document creates. Throws a 400 for a
     * document type that changes no charge terms (WAIVER, PAY_OR_VACATE).
     */
    private static TenancyTermSource chargeTermSourceFor(InstrumentRow row) {
        return TenancyTermSource.producedBy(row.type())
                .orElseThrow(() -> InvalidRequest.of("instrument.takes_no_charge_terms", row.type()));
    }

    /**
     * JSON has three types and Postgres has a dozen. The columns that need
     * help are the date and the uuid: both arrive as strings, and both mean
     * "clear it" when the string is empty, which is what an office worker
     * emptying a field on a form sends.
     */
    private static Map<String, Object> coerce(Map<String, Object> changes) {
        Map<String, Object> out = new LinkedHashMap<>(changes);
        if (out.get("term_start") instanceof String text) {
            out.put("term_start", text.isBlank() ? null : LocalDate.parse(text));
        }
        if (out.get("amends") instanceof String text) {
            out.put("amends", text.isBlank() ? null : UUID.fromString(text));
        }
        if (out.get("on_expiry") instanceof String text && text.isBlank()) {
            out.put("on_expiry", null);
        }
        return out;
    }

    /**
     * The one rule this service exists to hold: after DRAFT, a document is a
     * record of what went out rather than a thing being written.
     *
     * <p>A conflict rather than a not-found, and it names the status, because
     * "you cannot edit this" and "there is no such document" are different
     * things to see on a screen.
     */
    private static InstrumentRow requireDraft(InstrumentRow row) {
        if (!row.status().isEditable()) {
            throw RuleConflict.of("instrument.not_editable", row.status());
        }
        return row;
    }

    private void requireDraftFor(UUID instrumentUuid) {
        instrumentRepository.findById(instrumentUuid).ifPresent(InstrumentService::requireDraft);
    }
}

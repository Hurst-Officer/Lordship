package io.github.lordship.instruments;

import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.instruments.internal.InstrumentAdditionRepository;
import io.github.lordship.instruments.internal.InstrumentAdditionRow;
import io.github.lordship.instruments.internal.InstrumentRepository;
import io.github.lordship.instruments.internal.InstrumentRow;
import io.github.lordship.shared.ClauseBodyRules;
import io.github.lordship.shared.InstrumentType;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.tenancy.TenancyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
 * <p>This half is the draft: creating one, setting the term it covers, and the
 * clauses an office worker types onto it. Generating -- freezing the wording,
 * rendering the PDF, stamping the serial -- comes next and is what moves it out
 * of DRAFT for good.
 */
@Service
public class InstrumentService {

    private final InstrumentRepository instrumentRepository;
    private final InstrumentAdditionRepository additionRepository;
    private final TenancyService tenancyService;
    private final AuditService auditService;
    private final AuditContext auditContext;

    public InstrumentService(InstrumentRepository instrumentRepository,
                             InstrumentAdditionRepository additionRepository,
                             TenancyService tenancyService,
                             AuditService auditService,
                             AuditContext auditContext) {
        this.instrumentRepository = instrumentRepository;
        this.additionRepository = additionRepository;
        this.tenancyService = tenancyService;
        this.auditService = auditService;
        this.auditContext = auditContext;
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
     * Starts a document. Empty means no such tenancy.
     *
     * <p>Nothing is chosen yet but whose tenancy and what kind of paper -- no
     * template, no wording, no serial. Those are decided at generate, from the
     * park's assignment and the deal in force, which is why a draft created
     * today still prints next month's corrected clause.
     */
    @Transactional
    public Optional<Instrument> createDraft(UUID tenancy, InstrumentType type) {
        if (tenancyService.findTenancyById(tenancy).isEmpty()) {
            return Optional.empty();
        }

        InstrumentRow saved = instrumentRepository.save(
                tenancy, type, ActingAgent.resolve(auditContext));
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
        return Optional.of(afterOpt.get().toInstrument());
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

    // ---- internals -----------------------------------------------------------


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

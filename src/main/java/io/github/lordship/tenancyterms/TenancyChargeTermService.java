package io.github.lordship.tenancyterms;

import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.lots.Lot;
import io.github.lordship.lots.LotService;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.DomainProblem;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.shared.FeeMethod;
import io.github.lordship.shared.SecurityDepositMethod;
import io.github.lordship.shared.UtilityMethod;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancy.TenancyService;
import io.github.lordship.tenancyterms.internal.TenancyChargeTermRepository;
import io.github.lordship.tenancyterms.internal.TenancyChargeTermRow;
import io.github.lordship.termstemplate.TermsTemplate;
import io.github.lordship.termstemplate.TermsTemplateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;

@Service
public class TenancyChargeTermService {

    private static final BigDecimal MAX_DEPOSIT_MONTHS = new BigDecimal("5");

    private static final Set<String> LATE_FEE_METHODS = Set.of(
            FeeMethod.NONE.name(), FeeMethod.FLAT.name(), FeeMethod.PERCENT_OF_RENT.name());

    private static final Set<String> NSF_FEE_METHODS = Set.of(
            FeeMethod.NONE.name(), FeeMethod.FLAT.name(), FeeMethod.BANK_OR_FLAT.name());

    private static final Set<String> VIOLATION_FEE_METHODS = Set.of(
            FeeMethod.NONE.name(), FeeMethod.FLAT.name());

    private static final Set<String> UTILITY_METHODS = Set.of(
            UtilityMethod.NONE.name(), UtilityMethod.INCLUDED.name(), UtilityMethod.FLAT.name(),
            UtilityMethod.RUBS.name(), UtilityMethod.SUBMETERED.name());

    // Trash is collected per container, so there is nothing to submeter.
    private static final Set<String> TRASH_METHODS = Set.of(
            UtilityMethod.NONE.name(), UtilityMethod.INCLUDED.name(), UtilityMethod.FLAT.name(),
            UtilityMethod.RUBS.name());

    private static final Set<String> SECURITY_DEPOSIT_METHODS = Set.of(
            SecurityDepositMethod.NONE.name(), SecurityDepositMethod.FLAT.name(),
            SecurityDepositMethod.MULTIPLE_OF_RENT.name());

    private static final Set<String> FLAT_ONLY = Set.of(FeeMethod.FLAT.name());

    // The columns that may differ between the steps of one document's schedule.
    // Every other column is kept the same on all of them.
    private static final Set<String> PER_STEP_COLUMNS = Set.of("valid_at", "rate", "note");

    /**
     * A method column and the amount column it governs.
     *
     * <p>Three different shapes of rule, which is why the amount-bearing set is
     * per pair rather than shared. For a fee, only NONE means a zero amount. For
     * a utility, only FLAT carries one -- RUBS and SUBMETERED are computed from
     * real usage, so a flat amount alongside them is a mistake. For the deposit,
     * both paying methods carry a figure, but they are not the same KIND of
     * figure: FLAT holds dollars and MULTIPLE_OF_RENT holds a multiplier.
     */
    private record MethodAmountPair(
            String methodColumn,
            String amountColumn,
            Set<String> allowedMethods,
            Set<String> amountBearingMethods,
            Function<TenancyChargeTermRow, Enum<?>> currentMethod,
            Function<TenancyChargeTermRow, BigDecimal> currentAmount) {}

    private static final List<MethodAmountPair> METHOD_AMOUNT_PAIRS = List.of(
            new MethodAmountPair("late_fee_method", "late_fee_amount",
                    LATE_FEE_METHODS,
                    Set.of(FeeMethod.FLAT.name(), FeeMethod.PERCENT_OF_RENT.name()),
                    TenancyChargeTermRow::lateFeeMethod, TenancyChargeTermRow::lateFeeAmount),

            new MethodAmountPair("nsf_fee_method", "nsf_fee_amount",
                    NSF_FEE_METHODS,
                    Set.of(FeeMethod.FLAT.name(), FeeMethod.BANK_OR_FLAT.name()),
                    TenancyChargeTermRow::nsfFeeMethod, TenancyChargeTermRow::nsfFeeAmount),

            new MethodAmountPair("rule_violation_fee_method", "rule_violation_fee_amount",
                    VIOLATION_FEE_METHODS, FLAT_ONLY,
                    TenancyChargeTermRow::ruleViolationFeeMethod, TenancyChargeTermRow::ruleViolationFeeAmount),

            new MethodAmountPair("water_method", "water_flat_amount",
                    UTILITY_METHODS, FLAT_ONLY,
                    TenancyChargeTermRow::waterMethod, TenancyChargeTermRow::waterFlatAmount),

            new MethodAmountPair("power_method", "power_flat_amount",
                    UTILITY_METHODS, FLAT_ONLY,
                    TenancyChargeTermRow::powerMethod, TenancyChargeTermRow::powerFlatAmount),

            new MethodAmountPair("sewer_method", "sewer_flat_amount",
                    UTILITY_METHODS, FLAT_ONLY,
                    TenancyChargeTermRow::sewerMethod, TenancyChargeTermRow::sewerFlatAmount),

            new MethodAmountPair("trash_method", "trash_flat_amount",
                    TRASH_METHODS, FLAT_ONLY,
                    TenancyChargeTermRow::trashMethod, TenancyChargeTermRow::trashFlatAmount),

            // Not FLAT_ONLY: MULTIPLE_OF_RENT carries a multiplier, and zeroing it
            // here would fail term_deposit_amount_matches_method at submission.
            new MethodAmountPair("security_deposit_method", "security_deposit_amount",
                    SECURITY_DEPOSIT_METHODS,
                    Set.of(SecurityDepositMethod.FLAT.name(),
                            SecurityDepositMethod.MULTIPLE_OF_RENT.name()),
                    TenancyChargeTermRow::securityDepositMethod, TenancyChargeTermRow::securityDepositAmount));

    private final TenancyChargeTermRepository tenancyChargeTermRepository;
    private final TenancyService tenancyService;
    private final LotService lotService;
    private final TermsTemplateService termsTemplateService;
    private final AuditService auditService;
    private final AuditContext auditContext;

    public TenancyChargeTermService(TenancyChargeTermRepository tenancyChargeTermRepository,
                                    TenancyService tenancyService,
                                    LotService lotService,
                                    TermsTemplateService termsTemplateService,
                                    AuditService auditService,
                                    AuditContext auditContext) {
        this.tenancyChargeTermRepository = tenancyChargeTermRepository;
        this.tenancyService = tenancyService;
        this.lotService = lotService;
        this.termsTemplateService = termsTemplateService;
        this.auditService = auditService;
        this.auditContext = auditContext;
    }

    public Optional<TenancyChargeTerm> findById(UUID uuid) {
        return tenancyChargeTermRepository.findById(uuid)
                .map(TenancyChargeTermRow::toTenancyChargeTerm);
    }

    public List<ChargeTermConfiguration> findConfigurationsInForceByProperty(UUID propertyId) {
        return tenancyChargeTermRepository.findConfigurationsInForceByProperty(propertyId);
    }

    // The deal history for one tenancy, newest first.
    public List<TenancyChargeTerm> findByTenancy(UUID tenancy) {
        return tenancyChargeTermRepository.findByTenancy(tenancy).stream()
                .map(TenancyChargeTermRow::toTenancyChargeTerm)
                .toList();
    }

    // What billing asks: the term in force on the first day of the period.
    public Optional<TenancyChargeTerm> findInForceOn(UUID tenancy, LocalDate on) {
        return tenancyChargeTermRepository.findInForceOn(tenancy, on)
                .map(TenancyChargeTermRow::toTenancyChargeTerm);
    }

    /**
     * What this lot charged in each year of a range, for the rent-history
     * disclosure. Empty for a year nothing was in force -- the caller decides
     * what a year with no answer prints.
     */
    public List<RentHistoryYear> findRentHistoryByLot(UUID lotId, int fromYear, int toYear) {
        return tenancyChargeTermRepository.findRentHistoryByLot(lotId, fromYear, toYear);
    }

    /** Every charge term written for one document, earliest first. */
    public List<TenancyChargeTerm> findBySource(UUID sourceUuid) {
        return tenancyChargeTermRepository.findBySource(sourceUuid).stream()
                .map(TenancyChargeTermRow::toTenancyChargeTerm)
                .toList();
    }

    /**
     * Creates a MIGRATION or CORRECTION term: one an admin enters directly,
     * with no document behind it. Every setting is copied from the property's
     * terms template. The term starts as PROPOSED so it can be finished later.
     *
     * <p>All other terms are created from their document, through
     * {@link #createForDocument}.
     *
     * <p>A CORRECTION must have a reason. Other sources must not have one.
     *
     * <p>Returns empty if the tenancy does not exist. Throws a 409 if the lot
     * does not allow this agreement type or the property has no terms template
     * for it.
     */
    @Transactional
    public Optional<TenancyChargeTerm> createFromTemplate(UUID tenancy,
                                                          AgreementType agreementType,
                                                          LocalDate validAt,
                                                          TenancyTermSource source,
                                                          String correctionReason) {
        String reason = (correctionReason == null || correctionReason.isBlank())
                ? null
                : correctionReason.trim();

        if (source.requiresInstrument()) {
            throw InvalidRequest.onField("source", "term.needs_a_document", source);
        }
        if (source == TenancyTermSource.CORRECTION && reason == null) {
            throw InvalidRequest.onField("correctionReason", "term.correction_needs_reason");
        }
        if (source != TenancyTermSource.CORRECTION && reason != null) {
            throw InvalidRequest.onField("correctionReason", "term.reason_only_for_corrections");
        }

        Optional<LeaseContext> contextOpt = contextFor(tenancy, agreementType);
        if (contextOpt.isEmpty()) {
            return Optional.empty();
        }
        LeaseContext context = contextOpt.get();

        TenancyChargeTermRow row = TenancyChargeTermRow.fromTemplate(
                tenancy,
                context.template(),
                resolveRate(context.lot(), context.template(), source),
                validAt,
                source,
                null,
                ActingAgent.resolve(auditContext));

        return Optional.of(save(row.withCorrectionReason(reason)));
    }

    /**
     * The schedule the office worker is shown before committing to it. Pure
     * arithmetic against the property's template -- nothing is written here.
     *
     * <p>A template with no escalation yields a single step, so an ordinary land
     * lease and a five-year commercial lease take the same path; one just has a
     * schedule of length one.
     *
     * <p>termMonths comes from the lease being written, not from the template.
     * A template states how often rent escalates, never for how long, because
     * the term belongs to the paper.
     */
    public Optional<List<RentStep>> previewSchedule(UUID tenancy,
                                                    AgreementType agreementType,
                                                    LocalDate start,
                                                    int termMonths,
                                                    TenancyTermSource source) {
        return contextFor(tenancy, agreementType).map(context -> {
            TermsTemplate template = context.template();
            BigDecimal base = resolveRate(context.lot(), template, source);

            if (!template.hasEscalation()) {
                return List.of(new RentStep(start, base));
            }
            return buildSchedule(base, template.escalationPercent(), template.escalationMonths(),
                    termMonths, start);
        });
    }

    /**
     * Writes the rent schedule for one document. Each step becomes a PROPOSED
     * charge term linked to the document.
     *
     * <p>Only InstrumentService calls this. It has already checked that the
     * document is a DRAFT and that the source matches the document type.
     *
     * <p>The steps are saved exactly as given, so what the office worker
     * confirmed on screen is what gets written.
     *
     * <p>If the document already has charge terms, they are replaced. The new
     * steps keep the fees and settings of the old first step, so edits the
     * office worker already made are not lost. Only PROPOSED terms can be
     * replaced.
     *
     * <p>Returns empty if the tenancy does not exist.
     */
    @Transactional
    public Optional<List<TenancyChargeTerm>> createForDocument(UUID tenancy,
                                                               AgreementType agreementType,
                                                               List<RentStep> steps,
                                                               TenancyTermSource source,
                                                               UUID document) {
        if (steps == null || steps.isEmpty()) {
            throw InvalidRequest.of("term.schedule_needs_a_step");
        }

        Optional<LeaseContext> contextOpt = contextFor(tenancy, agreementType);
        if (contextOpt.isEmpty()) {
            return Optional.empty();
        }
        TermsTemplate template = contextOpt.get().template();

        List<TenancyChargeTermRow> existing = tenancyChargeTermRepository.findBySource(document);
        for (TenancyChargeTermRow row : existing) {
            if (row.status() != TenancyTermStatus.PROPOSED) {
                throw RuleConflict.of("term.schedule_already_submitted", row.status());
            }
        }
        for (TenancyChargeTermRow row : existing) {
            deleteChargeTerm(row.uuid());
        }

        UUID agent = ActingAgent.resolve(auditContext);
        List<TenancyChargeTerm> created = new ArrayList<>();
        for (RentStep step : steps) {
            TenancyChargeTermRow row = existing.isEmpty()
                    ? TenancyChargeTermRow.fromTemplate(
                            tenancy, template, step.rate(), step.validAt(), source, document, agent)
                    : TenancyChargeTermRow.copyForStep(
                            existing.get(0), step.rate(), step.validAt(), agent);
            created.add(save(row));
        }
        return Optional.of(created);
    }

    /**
     * Edits a PROPOSED term. Once a document is out for signature the terms
     * must match the paper, so they can no longer be edited.
     *
     * <p>If the term belongs to a document, fee and setting changes are copied
     * to every other PROPOSED term on that document. Only the date, rent and
     * note stay per step. This keeps a multi-year schedule consistent: changing
     * the pet fee on year one changes it on every year.
     */
    @Transactional
    public Optional<TenancyChargeTerm> patchChargeTerm(UUID uuid, Map<String, Object> changes) {
        Optional<TenancyChargeTermRow> beforeOpt = tenancyChargeTermRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        TenancyChargeTermRow before = beforeOpt.get();

        if (!before.status().isEditable()) {
            throw InvalidRequest.of("term.not_editable", before.status());
        }
        Map<String, Object> coerced = coerce(changes);

        Optional<TenancyChargeTermRow> afterOpt = patchOne(before, new LinkedHashMap<>(coerced));
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }

        Map<String, Object> shared = new LinkedHashMap<>(coerced);
        shared.keySet().removeAll(PER_STEP_COLUMNS);

        if (before.sourceUuid() != null && !shared.isEmpty()) {
            for (TenancyChargeTermRow sibling : tenancyChargeTermRepository.findBySource(before.sourceUuid())) {
                if (!sibling.uuid().equals(uuid) && sibling.status() == TenancyTermStatus.PROPOSED) {
                    patchOne(sibling, new LinkedHashMap<>(shared));
                }
            }
        }

        return Optional.of(afterOpt.get().toTenancyChargeTerm());
    }

    /** Applies one patch to one term and writes the change to the audit log. */
    private Optional<TenancyChargeTermRow> patchOne(TenancyChargeTermRow before, Map<String, Object> changes) {
        reconcileMethodAmountPairs(before, changes);

        Optional<TenancyChargeTermRow> afterOpt = tenancyChargeTermRepository.patch(before.uuid(), changes);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }
        TenancyChargeTermRow after = afterOpt.get();

        AuditMapper.Diff diff = AuditMapper.diff(before, after);
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("tenancy_charge_term", before.uuid(), diff.before(), diff.after());
        }
        return Optional.of(after);
    }

    /**
     * PROPOSED to PENDING: the draft is finished and a document is going out.
     *
     * <p>This is the transition the escaped CHECK constraints were waiting for.
     * Every one of them reads {@code status = 'PROPOSED' OR ...}, so Postgres
     * re-evaluates the lot of them on this UPDATE and a draft that was legal to
     * save becomes illegal to submit. Validating first is what turns a
     * DataIntegrityViolationException into a 400 naming the fields at fault.
     */
    @Transactional
    public Optional<TenancyChargeTerm> submit(UUID uuid) {
        return transition(uuid, TenancyTermStatus.PROPOSED, TenancyTermStatus.PENDING,
                TenancyChargeTermService::validateForSubmission);
    }

    /**
     * PENDING to ACTIVE: the document came back signed or served, and the deal
     * is in force from valid_at. term_in_force_needs_paper is checked here so a
     * term with nothing behind it is refused by name rather than by constraint.
     */
    @Transactional
    public Optional<TenancyChargeTerm> activate(UUID uuid) {
        return transition(uuid, TenancyTermStatus.PENDING, TenancyTermStatus.ACTIVE, row -> {
            if (row.source().requiresInstrument() && row.sourceUuid() == null) {
                throw InvalidRequest.of("term.no_instrument");
            }
        });
    }

    /**
     * Ends a term that HAS gone into effect. A term that never went into effect
     * is deleted instead -- see {@link #deleteChargeTerm(UUID)}.
     *
     * <p>Empty means no ACTIVE term with that id, which covers both "no such
     * term" and "that term was never in force".
     */
    @Transactional
    public Optional<TenancyChargeTerm> cancel(UUID uuid, String cancelReason) {
        if (cancelReason == null || cancelReason.isBlank()) {
            throw InvalidRequest.of("term.cancel_needs_reason");
        }

        Optional<TenancyChargeTermRow> beforeOpt = tenancyChargeTermRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        TenancyChargeTermRow before = beforeOpt.get();

        Optional<TenancyChargeTermRow> afterOpt = tenancyChargeTermRepository.cancel(
                uuid, ActingAgent.resolve(auditContext), cancelReason.trim());
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }
        TenancyChargeTermRow after = afterOpt.get();

        AuditMapper.Diff diff = AuditMapper.diff(before, after);
        auditService.recordUpdate("tenancy_charge_term", uuid, diff.before(), diff.after());
        return Optional.of(after.toTenancyChargeTerm());
    }

    /**
     * Soft delete, and only for a term that never generated charges. The
     * repository carries the status guard, so an in-force term answers false
     * rather than raising term_delete_only_before_force.
     */
    @Transactional
    public boolean deleteChargeTerm(UUID uuid) {
        return tenancyChargeTermRepository.findById(uuid).map(existing -> {
            if (!tenancyChargeTermRepository.softDelete(uuid)) {
                return false;
            }
            auditService.recordDelete("tenancy_charge_term", uuid, AuditMapper.toMap(existing));
            return true;
        }).orElse(false);
    }

    // ---- whole-document operations ------------------------------------------
    // Every charge term written for one document is found by its source_uuid.
    // These methods act on all of them at once. Each one calls the single-term
    // method, so the checks, audit entries and error messages are the same.

    /**
     * PROPOSED to PENDING for every term on the document. Called at generate.
     * Terms that are already PENDING are left as they are.
     */
    @Transactional
    public List<TenancyChargeTerm> submitForDocument(UUID document) {
        List<TenancyChargeTerm> moved = new ArrayList<>();
        for (TenancyChargeTermRow row : tenancyChargeTermRepository.findBySource(document)) {
            if (row.status() == TenancyTermStatus.PROPOSED) {
                submit(row.uuid()).ifPresent(moved::add);
            }
        }
        return moved;
    }

    /** PENDING to ACTIVE for every term on the document. Called at approve. */
    @Transactional
    public List<TenancyChargeTerm> activateForDocument(UUID document) {
        List<TenancyChargeTerm> moved = new ArrayList<>();
        for (TenancyChargeTermRow row : tenancyChargeTermRepository.findBySource(document)) {
            activate(row.uuid()).ifPresent(moved::add);
        }
        return moved;
    }

    /**
     * Cancels the document's terms that have not taken effect yet. Used when a
     * lease ends early.
     *
     * <p>Terms whose valid_at has already passed are left alone. They really were
     * in force, and cancelling them would remove that year from the rent history.
     * Ending a tenancy is done with tenancy.end_date, not by cancelling terms.
     */
    @Transactional
    public List<TenancyChargeTerm> cancelFutureForDocument(UUID document, String cancelReason) {
        LocalDate today = LocalDate.now();
        List<TenancyChargeTerm> cancelled = new ArrayList<>();

        for (TenancyChargeTermRow row : tenancyChargeTermRepository.findBySource(document)) {
            if (row.status() == TenancyTermStatus.ACTIVE && row.validAt().isAfter(today)) {
                cancel(row.uuid(), cancelReason).ifPresent(cancelled::add);
            }
        }
        return cancelled;
    }

    /**
     * Deletes the document's terms that never went into force. Called when the
     * document is abandoned. Returns how many were deleted.
     */
    @Transactional
    public int deleteForDocument(UUID document) {
        int deleted = 0;
        for (TenancyChargeTermRow row : tenancyChargeTermRepository.findBySource(document)) {
            if (deleteChargeTerm(row.uuid())) {
                deleted++;
            }
        }
        return deleted;
    }

    // ---- internals ---------------------------------------------------------

    /** The lot and template a charge term for this tenancy is built from. */
    private record LeaseContext(Lot lot, TermsTemplate template) {}

    /**
     * Resolves the two things every create needs, and applies the two gates.
     *
     * <p>Empty means the tenancy does not exist. The gates throw instead,
     * because they are rule violations rather than missing records: the lot
     * gate asks whether the space can host the deal at all -- a park that does
     * RV lots still cannot put an RV agreement on a storage locker -- while the
     * template lookup asks the separate question of whether the property offers
     * that kind of deal.
     */
    private Optional<LeaseContext> contextFor(UUID tenancy, AgreementType agreementType) {
        Optional<Tenancy> tenancyOpt = tenancyService.findTenancyById(tenancy);
        if (tenancyOpt.isEmpty()) {
            return Optional.empty();
        }

        Lot lot = lotService.findById(tenancyOpt.get().lotId())
                .orElseThrow(() -> RuleConflict.of("lot.no_longer_exists", tenancy));

        if (!lot.permits(agreementType)) {
            throw RuleConflict.of("lot.does_not_permit", lot.lotNumber(), agreementType);
        }

        TermsTemplate template = termsTemplateService
                .findForProperty(lot.propertyId(), agreementType)
                .orElseThrow(() -> RuleConflict.of("property.no_template", agreementType));

        return Optional.of(new LeaseContext(lot, template));
    }

    /** Saves a new term and writes it to the audit log. */
    private TenancyChargeTerm save(TenancyChargeTermRow row) {
        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(row);
        auditService.recordInsert("tenancy_charge_term", saved.uuid(), AuditMapper.toMap(saved));
        return saved.toTenancyChargeTerm();
    }

    /**
     * The dated rates a lease with an escalation clause will bill.
     *
     * <p>Each step compounds on the previous step's ROUNDED figure, not on the
     * base rate raised to a power. That is how the schedule is drafted -- year
     * three is year two's stated rent plus the percentage -- and the stated
     * figures are what the tenant signs.
     *
     * <p>The loop also handles a term that is not a whole number of escalation
     * periods: a thirty month lease escalating annually gets three steps, the
     * last of which runs six months.
     *
     * <p>public and static so it can be tested as the arithmetic it is, without
     * standing up the service.
     */
    public static List<RentStep> buildSchedule(BigDecimal baseRate,
                                               BigDecimal escalationPercent,
                                               int escalationMonths,
                                               int termMonths,
                                               LocalDate start) {
        List<RentStep> steps = new ArrayList<>();
        BigDecimal rate = baseRate.setScale(2, RoundingMode.HALF_UP);
        BigDecimal multiplier = BigDecimal.ONE.add(escalationPercent.movePointLeft(2));

        for (int month = 0; month < termMonths; month += escalationMonths) {
            steps.add(new RentStep(start.plusMonths(month), rate));
            rate = rate.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
        }
        return steps;
    }

    private Optional<TenancyChargeTerm> transition(UUID uuid,
                                                   TenancyTermStatus from,
                                                   TenancyTermStatus to,
                                                   java.util.function.Consumer<TenancyChargeTermRow> guard) {
        Optional<TenancyChargeTermRow> beforeOpt = tenancyChargeTermRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        TenancyChargeTermRow before = beforeOpt.get();

        if (before.status() != from) {
            throw InvalidRequest.of("term.wrong_status", before.status(), from, to);
        }

        guard.accept(before);

        Optional<TenancyChargeTermRow> afterOpt = tenancyChargeTermRepository.updateStatus(uuid, from, to);
        if (afterOpt.isEmpty()) {
            // Somebody else moved it between the read and the write.
            return Optional.empty();
        }
        TenancyChargeTermRow after = afterOpt.get();

        AuditMapper.Diff diff = AuditMapper.diff(before, after);
        auditService.recordUpdate("tenancy_charge_term", uuid, diff.before(), diff.after());
        return Optional.of(after.toTenancyChargeTerm());
    }

    /**
     * The lot's rate for this agreement type is the authority -- rates are set
     * while looking at lots on the map, not while editing terms. The property's
     * template is the fallback. Zero when neither has one, which is legal for a
     * draft and refused at submission.
     *
     * <p>Which of the two rates applies depends on who is signing. A tenancy
     * starting fresh is quoted the asking rate; an increase notice is the
     * instrument that steers an existing tenancy toward the target. How far it
     * may move in one step is the rent-increase engine's problem, not this
     * method's -- here the target is simply where it is headed.
     */
    private static BigDecimal resolveRate(Lot lot, TermsTemplate template, TenancyTermSource source) {
        AgreementType agreementType = template.agreementType();

        if (source == TenancyTermSource.INCREASE_NOTICE) {
            return lot.targetRateFor(agreementType)
                    .or(() -> Optional.ofNullable(template.targetRate()))
                    .orElse(BigDecimal.ZERO);
        }

        return lot.askingRateFor(agreementType)
                .or(() -> Optional.ofNullable(template.askingRate()))
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Everything the escaped CHECK constraints will test the moment this row
     * stops being PROPOSED, collected into one message so an office worker
     * fixes the whole form at once instead of one field per round trip.
     */
    private static void validateForSubmission(TenancyChargeTermRow row) {
        List<DomainProblem.Problem> problems = new ArrayList<>();

        if (row.rate().signum() <= 0) {
            problems.add(DomainProblem.Problem.onField(
                    "rate", "term.rate_not_set", row.agreementType()));
        }

        if (row.securityDepositMethod() == SecurityDepositMethod.MULTIPLE_OF_RENT
                && row.securityDepositAmount().compareTo(MAX_DEPOSIT_MONTHS) > 0) {
            problems.add(DomainProblem.Problem.onField(
                    "security_deposit_amount", "term.deposit_above_ceiling",
                    row.securityDepositAmount(),
                    row.rate(),
                    row.rate().multiply(row.securityDepositAmount()).setScale(2, RoundingMode.HALF_UP),
                    MAX_DEPOSIT_MONTHS));
        }

        // term_cars_max_at_least_allowed
        if (row.carsMax() < row.allowedCars()) {
            problems.add(DomainProblem.Problem.onField(
                    "cars_max", "term.cars_max_below_allowed", row.carsMax(), row.allowedCars()));
        }

        for (MethodAmountPair pair : METHOD_AMOUNT_PAIRS) {
            String method = nameOf(pair.currentMethod().apply(row));
            BigDecimal amount = pair.currentAmount().apply(row);

            if (method != null && pair.amountBearingMethods().contains(method)) {
                if (amount.signum() <= 0) {
                    problems.add(DomainProblem.Problem.onField(pair.amountColumn(),
                            "term.amount_required", pair.methodColumn(), method));
                }
            } else if (amount.signum() != 0) {
                problems.add(DomainProblem.Problem.onField(pair.amountColumn(),
                        "term.amount_must_be_zero", pair.methodColumn(), method));
            }
        }

        if (!problems.isEmpty()) {
            throw InvalidRequest.withDetails("term.not_ready_to_submit", problems);
        }
    }

    /**
     * Keeps a patch from leaving a pair in a state the database will reject
     * later, without blocking an unfinished draft.
     *
     * <p>Two different jobs, and the split matters. Rejecting a method the
     * column does not allow is always right -- SUBMETERED trash is never going
     * to become valid. Zeroing an amount whose method stopped carrying one is
     * mechanical and safe. But refusing a patch because a FLAT fee has no
     * amount yet would break incremental editing, which is the entire reason
     * PROPOSED exists -- so that check waits for {@link #submit(UUID)}.
     */
    private static void reconcileMethodAmountPairs(TenancyChargeTermRow before, Map<String, Object> changes) {
        for (MethodAmountPair pair : METHOD_AMOUNT_PAIRS) {
            boolean methodTouched = changes.containsKey(pair.methodColumn());
            boolean amountTouched = changes.containsKey(pair.amountColumn());
            if (!methodTouched && !amountTouched) {
                continue;
            }

            String method = nameOf(pair.currentMethod().apply(before));
            if (methodTouched) {
                Object raw = changes.get(pair.methodColumn());
                method = (raw == null) ? null : raw.toString().trim().toUpperCase(Locale.ROOT);
                // The null check is not decoration: Set.of(...) throws on a null
                // probe rather than answering false.
                if (method == null || !pair.allowedMethods().contains(method)) {
                    throw InvalidRequest.onField(pair.methodColumn(),
                            "term.method_not_allowed", pair.allowedMethods());
                }
                changes.put(pair.methodColumn(), method);
            }

            if (method == null || !pair.amountBearingMethods().contains(method)) {
                changes.put(pair.amountColumn(), BigDecimal.ZERO);
                continue;
            }

            if (amountTouched) {
                BigDecimal amount = toAmount(changes.get(pair.amountColumn()), pair.amountColumn());
                if (amount != null && amount.signum() < 0) {
                    throw InvalidRequest.onField(pair.amountColumn(), "term.amount_negative");
                }
                changes.put(pair.amountColumn(), amount == null ? BigDecimal.ZERO : amount);
            }
        }
    }

    private static Map<String, Object> coerce(Map<String, Object> changes) {

        Map<String, Object> coerced = new LinkedHashMap<>(changes);
        coerced.replaceAll((column, value) -> switch (column) {
            case "valid_at" -> toDate(column, value);
            default -> value;
        });
        return coerced;
    }

    private static Object toDate(String column, Object raw) {
        if (raw == null || raw instanceof LocalDate) {
            return raw;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw InvalidRequest.onField(column, "term.not_a_date", text);
        }
    }

    private static BigDecimal toAmount(Object raw, String column) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            String text = raw.toString().trim();
            return text.isEmpty() ? null : new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw InvalidRequest.onField(column, "term.not_a_number");
        }
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
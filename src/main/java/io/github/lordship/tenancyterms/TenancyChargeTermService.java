package io.github.lordship.tenancyterms;

import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.lots.Lot;
import io.github.lordship.lots.LotService;
import io.github.lordship.shared.AgreementType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
            UtilityMethod.NONE.name(), UtilityMethod.FLAT.name(),
            UtilityMethod.RUBS.name(), UtilityMethod.SUBMETERED.name());

    // Trash is collected per container, so there is nothing to submeter.
    private static final Set<String> TRASH_METHODS = Set.of(
            UtilityMethod.NONE.name(), UtilityMethod.FLAT.name(), UtilityMethod.RUBS.name());

    private static final Set<String> SECURITY_DEPOSIT_METHODS = Set.of(
            SecurityDepositMethod.NONE.name(), SecurityDepositMethod.FLAT.name(),
            SecurityDepositMethod.MULTIPLE_OF_RENT.name());

    private static final Set<String> FLAT_ONLY = Set.of(FeeMethod.FLAT.name());

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

    // One bulk run, so it can be reviewed or abandoned together.
    public List<TenancyChargeTerm> findByBatch(UUID batch) {
        return tenancyChargeTermRepository.findByBatch(batch).stream()
                .map(TenancyChargeTermRow::toTenancyChargeTerm)
                .toList();
    }

    /**
     * Creates a term by copying the property's template for this agreement type.
     * There is no blank create: every value column is NOT NULL with no default,
     * so the copy is the create. Lands in PROPOSED with no instrument attached,
     * which is what lets an incomplete draft be saved and finished later.
     *
     * <p>Empty means the tenancy does not exist. A lot that does not permit the
     * agreement type, or a property that was never given a template for it, is a
     * rule violation rather than a missing record.
     */
    @Transactional
    public Optional<TenancyChargeTerm> createFromTemplate(UUID tenancy,
                                                          AgreementType agreementType,
                                                          LocalDate validAt,
                                                          TenancyTermSource source,
                                                          UUID batch) {
        Optional<Tenancy> tenancyOpt = tenancyService.findTenancyById(tenancy);
        if (tenancyOpt.isEmpty()) {
            return Optional.empty();
        }

        Lot lot = lotService.findById(tenancyOpt.get().lotId())
                .orElseThrow(() -> new IllegalStateException(
                        "Tenancy " + tenancy + " points at a lot that no longer exists"));

        // Two independent gates. This one asks whether the space can host the
        // deal at all -- a park that does RV lots still cannot put an RV
        // agreement on a storage locker. The template lookup below asks the
        // separate question of whether the property offers that kind of deal.
        if (!lot.permits(agreementType)) {
            throw new IllegalStateException(
                    "Lot " + lot.lotNumber() + " does not permit " + agreementType + " agreements");
        }

        TermsTemplate template = termsTemplateService
                .findForProperty(lot.propertyId(), agreementType)
                .orElseThrow(() -> new IllegalStateException(
                        "This property has no terms template for " + agreementType
                                + ", so it cannot host that kind of agreement"));

        TenancyChargeTermRow saved = tenancyChargeTermRepository.save(
                TenancyChargeTermRow.fromTemplate(
                        tenancy,
                        template,
                        resolveRate(lot, template, source),
                        validAt,
                        source,
                        batch,
                        ActingAgent.resolve(auditContext)));

        auditService.recordInsert("tenancy_charge_term", saved.uuid(), AuditMapper.toMap(saved));
        return Optional.of(saved.toTenancyChargeTerm());
    }

    /**
     * Edits a draft. Only PROPOSED terms are editable: once a document is out
     * for signature the deal on paper and the deal in the database have to stay
     * the same, so a change after that is a new term, not an edit.
     */
    @Transactional
    public Optional<TenancyChargeTerm> patchChargeTerm(UUID uuid, Map<String, Object> changes) {
        Optional<TenancyChargeTermRow> beforeOpt = tenancyChargeTermRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        TenancyChargeTermRow before = beforeOpt.get();

        if (!before.status().isEditable()) {
            throw new IllegalArgumentException(
                    "This term is " + before.status() + " and can no longer be edited; create a new term instead");
        }

        reconcileMethodAmountPairs(before, changes);

        Optional<TenancyChargeTermRow> afterOpt = tenancyChargeTermRepository.patch(uuid, changes);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }
        TenancyChargeTermRow after = afterOpt.get();

        AuditMapper.Diff diff = AuditMapper.diff(before, after);
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("tenancy_charge_term", uuid, diff.before(), diff.after());
        }

        return Optional.of(after.toTenancyChargeTerm());
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
            if (row.source() != TenancyTermSource.MIGRATION && row.sourceUuid() == null) {
                throw new IllegalArgumentException(
                        "This term cannot go into force: no instrument is attached, and only "
                                + "migrated terms are allowed to have none");
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
            throw new IllegalArgumentException("A cancellation needs a reason");
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
     * Records the instrument that produced this deal. Kept off PATCH because of
     * the composite foreign key to instrument(uuid, tenancy): the database is
     * what guarantees a document from another tenancy cannot be attached here.
     */
    @Transactional
    public Optional<TenancyChargeTerm> attachSource(UUID uuid, UUID sourceUuid) {
        Optional<TenancyChargeTermRow> beforeOpt = tenancyChargeTermRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        TenancyChargeTermRow before = beforeOpt.get();

        Optional<TenancyChargeTermRow> afterOpt = tenancyChargeTermRepository.attachSource(uuid, sourceUuid);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }
        TenancyChargeTermRow after = afterOpt.get();

        AuditMapper.Diff diff = AuditMapper.diff(before, after);
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("tenancy_charge_term", uuid, diff.before(), diff.after());
        }
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

    // ---- internals ---------------------------------------------------------

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
            throw new IllegalArgumentException(
                    "This term is " + before.status() + "; only a " + from + " term can become " + to);
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
        List<String> problems = new ArrayList<>();

        if (row.rate().signum() <= 0) {
            problems.add("rate: no target rate is set for " + row.agreementType()
                    + " on this lot or on the property's template");
        }

        if (row.securityDepositMethod() == SecurityDepositMethod.MULTIPLE_OF_RENT
                && row.securityDepositAmount().compareTo(MAX_DEPOSIT_MONTHS) > 0) {
            problems.add("security_deposit_amount: " + row.securityDepositAmount()
                    + " is a multiplier, not dollars -- at a rate of " + row.rate()
                    + " that is a deposit of "
                    + row.rate().multiply(row.securityDepositAmount()).setScale(2, RoundingMode.HALF_UP)
                    + ". The most months' rent allowed is " + MAX_DEPOSIT_MONTHS);
        }

        // term_cars_max_at_least_allowed
        if (row.carsMax() < row.allowedCars()) {
            problems.add("carsMax: cannot be lower than allowedCars ("
                    + row.carsMax() + " < " + row.allowedCars() + ")");
        }

        for (MethodAmountPair pair : METHOD_AMOUNT_PAIRS) {
            String method = nameOf(pair.currentMethod().apply(row));
            BigDecimal amount = pair.currentAmount().apply(row);

            if (method != null && pair.amountBearingMethods().contains(method)) {
                if (amount.signum() <= 0) {
                    problems.add(pair.amountColumn() + ": must be greater than zero when "
                            + pair.methodColumn() + " is " + method);
                }
            } else if (amount.signum() != 0) {
                problems.add(pair.amountColumn() + ": must be zero when "
                        + pair.methodColumn() + " is " + method);
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(
                    "This term is not ready to submit -- " + String.join("; ", problems));
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
                    throw new IllegalArgumentException(
                            pair.methodColumn() + " must be one of " + pair.allowedMethods());
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
                    throw new IllegalArgumentException(pair.amountColumn() + " cannot be negative");
                }
                changes.put(pair.amountColumn(), amount == null ? BigDecimal.ZERO : amount);
            }
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
            throw new IllegalArgumentException(column + " must be a number");
        }
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
package io.github.lordship.termstemplate;

import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.DomainProblem;
import io.github.lordship.shared.FeeMethod;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.LateFeeTier;
import io.github.lordship.shared.Patches;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.shared.SecurityDepositMethod;
import io.github.lordship.termstemplate.internal.TermsTemplateRepository;
import io.github.lordship.termstemplate.internal.TermsTemplateRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Service
public class TermsTemplateService {

    private static final Set<String> LATE_FEE_METHODS = Set.of(
            FeeMethod.NONE.name(), FeeMethod.FLAT.name(), FeeMethod.PERCENT_OF_RENT.name(),
            FeeMethod.TIERED.name());

    private static final Set<String> NSF_FEE_METHODS = Set.of(
            FeeMethod.NONE.name(), FeeMethod.FLAT.name(), FeeMethod.BANK_OR_FLAT.name());

    private static final Set<String> VIOLATION_FEE_METHODS = Set.of(
            FeeMethod.NONE.name(), FeeMethod.FLAT.name());

    private static final Set<String> UTILITY_METHODS = Set.of("NONE", "INCLUDED", "FLAT", "RUBS", "SUBMETERED");

    private static final Set<String> TRASH_METHODS = Set.of("NONE", "INCLUDED", "FLAT", "RUBS");

    private static final Set<String> SECURITY_DEPOSIT_METHODS = Set.of("NONE", "FLAT", "MULTIPLE_OF_RENT");

    // if only these things change - DO NOT do an audit log
    private static final Set<String> HOUSEKEEPING_KEYS = Set.of("updatedAt");

    // The NUMERIC(12,2) columns: dollars and cents (or, for a late fee and a deposit, a percent or a multiple)
    private static final Set<String> AMOUNT_COLUMNS = Set.of(
            "target_rate", "asking_rate", "car_fee", "pet_fee",
            "rule_violation_fee_amount", "nsf_fee_amount", "late_fee_amount",
            "water_flat_amount", "power_flat_amount", "sewer_flat_amount", "trash_flat_amount",
            "security_deposit_amount");

    // The INT columns: counts and days
    private static final Set<String> WHOLE_COLUMNS = Set.of(
            "escalation_months", "allowed_cars", "cars_max", "allowed_pets",
            "payment_due_day", "grace_period_days");

    /**
     * One method column and the amount column it governs.
     *
     * <p>{@code methodsCarryingAmount} is per-pair rather than global because the
     * rule differs by column: a late fee carries an amount on PERCENT_OF_RENT, an
     * NSF fee on BANK_OR_FLAT, a deposit on MULTIPLE_OF_RENT, a utility on FLAT
     * alone. It mirrors the amount-matches-method CHECK on each pair, so the
     * service and the schema refuse the same rows.
     */
    private record MethodAmountPair(
            String methodColumn,
            String amountColumn,
            Set<String> allowedMethods,
            Set<String> methodsCarryingAmount,
            Function<TermsTemplateRow, Enum<?>> currentMethod,
            Function<TermsTemplateRow, BigDecimal> currentAmount) {}

    private static final List<MethodAmountPair> METHOD_AMOUNT_PAIRS = List.of(
            new MethodAmountPair("late_fee_method", "late_fee_amount", LATE_FEE_METHODS,
                    Set.of("FLAT", "PERCENT_OF_RENT"),
                    TermsTemplateRow::lateFeeMethod, TermsTemplateRow::lateFeeAmount),
            new MethodAmountPair("rule_violation_fee_method", "rule_violation_fee_amount", VIOLATION_FEE_METHODS,
                    Set.of("FLAT"),
                    TermsTemplateRow::ruleViolationFeeMethod, TermsTemplateRow::ruleViolationFeeAmount),
            new MethodAmountPair("nsf_fee_method", "nsf_fee_amount", NSF_FEE_METHODS,
                    Set.of("FLAT", "BANK_OR_FLAT"),
                    TermsTemplateRow::nsfFeeMethod, TermsTemplateRow::nsfFeeAmount),
            new MethodAmountPair("water_method", "water_flat_amount", UTILITY_METHODS,
                    Set.of("FLAT"),
                    TermsTemplateRow::waterMethod, TermsTemplateRow::waterFlatAmount),
            new MethodAmountPair("power_method", "power_flat_amount", UTILITY_METHODS,
                    Set.of("FLAT"),
                    TermsTemplateRow::powerMethod, TermsTemplateRow::powerFlatAmount),
            new MethodAmountPair("sewer_method", "sewer_flat_amount", UTILITY_METHODS,
                    Set.of("FLAT"),
                    TermsTemplateRow::sewerMethod, TermsTemplateRow::sewerFlatAmount),
            new MethodAmountPair("trash_method", "trash_flat_amount", TRASH_METHODS,
                    Set.of("FLAT"),
                    TermsTemplateRow::trashMethod, TermsTemplateRow::trashFlatAmount),
            new MethodAmountPair("security_deposit_method", "security_deposit_amount", SECURITY_DEPOSIT_METHODS,
                    Set.of("FLAT", "MULTIPLE_OF_RENT"),
                    TermsTemplateRow::securityDepositMethod, TermsTemplateRow::securityDepositAmount));

    private final TermsTemplateRepository termsTemplateRepository;
    private final AuditService auditService;
    private final AuditContext auditContext;

    public TermsTemplateService(TermsTemplateRepository termsTemplateRepository,
                                AuditService auditService,
                                AuditContext auditContext) {
        this.termsTemplateRepository = termsTemplateRepository;
        this.auditService = auditService;
        this.auditContext = auditContext;
    }

    public Optional<TermsTemplate> findById(UUID uuid) {
        return termsTemplateRepository.findById(uuid).map(TermsTemplateRow::toTermsTemplate);
    }

    // The deal types this property may offer.
    public List<TermsTemplate> findByProperty(UUID property) {
        return termsTemplateRepository.findByProperty(property).stream()
                .map(TermsTemplateRow::toTermsTemplate)
                .toList();
    }

    // The admin-only pool a property copies from.
    public List<TermsTemplate> findGlobalTemplates() {
        return termsTemplateRepository.findGlobalTemplates().stream()
                .map(TermsTemplateRow::toTermsTemplate)
                .toList();
    }

    // Used when a tenancy is created: the terms it starts from.
    public Optional<TermsTemplate> findForProperty(UUID property, AgreementType agreementType) {
        return termsTemplateRepository.findByPropertyAndAgreementType(property, agreementType)
                .map(TermsTemplateRow::toTermsTemplate);
    }

    @Transactional
    public TermsTemplate createGlobalTemplate(String name, AgreementType agreementType) {
        requireGlobalNameIsFree(name);

        TermsTemplateRow saved = termsTemplateRepository.save(
                new TermsTemplateRow(null, name, agreementType, ActingAgent.resolve(auditContext)));

        auditService.recordInsert("terms_template", saved.uuid(), AuditMapper.toMap(saved));
        return saved.toTermsTemplate();
    }

    // A property gains an agreement type only by an admin copying a global template in.
    // Empty means the template does not exist.
    @Transactional
    public Optional<TermsTemplate> copyTemplateToProperty(UUID templateUuid, UUID property) {
        Optional<TermsTemplateRow> templateOpt = termsTemplateRepository.findById(templateUuid);
        if (templateOpt.isEmpty()) {
            return Optional.empty();
        }
        TermsTemplateRow template = templateOpt.get();

        if (template.property() != null) {
            throw new IllegalArgumentException("Only a global template can be copied into a property");
        }
        requirePropertyScopeIsFree(property, template.agreementType());

        TermsTemplateRow saved = termsTemplateRepository.saveCopy(template.copyTo(property, ActingAgent.resolve(auditContext)));
        auditService.recordInsert("terms_template", saved.uuid(), AuditMapper.toMap(saved));
        return Optional.of(saved.toTermsTemplate());
    }

    @Transactional
    public Optional<TermsTemplate> patchTermsTemplate(UUID uuid, Map<String, Object> changes) {
        Optional<TermsTemplateRow> beforeOpt = termsTemplateRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        TermsTemplateRow before = beforeOpt.get();

        changes = withTextNumbersRead(changes);

        if (changes.containsKey("late_fee_tiers")) {
            changes.put("late_fee_tiers", LateFeeTier.fromRequest(changes.get("late_fee_tiers")));
        }

        validateDepositCeiling(before, changes);
        reconcileMethodAmountPairs(before, changes);
        reconcileLateFeeTiers(before, changes);

        Optional<TermsTemplateRow> afterOpt =
                termsTemplateRepository.patch(uuid, changes);

        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }

        TermsTemplateRow after = afterOpt.get();

        AuditMapper.Diff diff = AuditMapper.diff(before, after);
        Map<String, Object> changedBefore = withoutHousekeeping(diff.before());
        Map<String, Object> changedAfter = withoutHousekeeping(diff.after());

        if (!changedBefore.isEmpty()) {
            auditService.recordUpdate("terms_template", uuid, changedBefore, changedAfter);
        }

        return Optional.of(after.toTermsTemplate());
    }

    /**
     * Glade sends amounts and counts as text ("650.00", "2"), so every digit arrives: a JSON number in a map
     * body arrives as a double. Each number sent as text becomes a number the number's column holds exactly:
     * an amount with more than two decimals, or a count that is not whole, is refused instead of rounded.
     * Blank text is null. A value that arrived as a JSON number is left as the value arrived.
     *
     * <p>The caller's map is not changed: when any value is read, the result is a copy.
     */
    private static Map<String, Object> withTextNumbersRead(Map<String, Object> changes) {
        Map<String, Object> read = null;
        for (Map.Entry<String, Object> entry : changes.entrySet()) {
            String column = entry.getKey();
            if (!(entry.getValue() instanceof String)) {
                continue;
            }
            Object number;
            if (AMOUNT_COLUMNS.contains(column)) {
                number = Patches.fits(Patches.decimal(entry.getValue(), column), 12, 2, column);
            } else if (column.equals("escalation_percent")) {
                number = Patches.fits(Patches.decimal(entry.getValue(), column), 5, 2, column);
            } else if (WHOLE_COLUMNS.contains(column)) {
                Long whole = Patches.whole(entry.getValue(), column);
                if (whole != null && (whole > Integer.MAX_VALUE || whole < Integer.MIN_VALUE)) {
                    throw InvalidRequest.onField(column, "value.too_large");
                }
                number = (whole == null) ? null : whole.intValue();
            } else {
                continue;
            }
            if (read == null) {
                read = new HashMap<>(changes);
            }
            read.put(column, number);
        }
        return (read == null) ? changes : read;
    }

    private static void validateDepositCeiling(TermsTemplateRow before, Map<String, Object> changes) {
        String method = changes.containsKey("security_deposit_method")
                ? String.valueOf(changes.get("security_deposit_method"))
                : nameOf(before.securityDepositMethod());

        if (!SecurityDepositMethod.MULTIPLE_OF_RENT.name().equals(method)) {
            return;
        }

        BigDecimal amount = changes.containsKey("security_deposit_amount")
                ? toAmount(changes.get("security_deposit_amount"), "security_deposit_amount")
                : before.securityDepositAmount();

        if (amount != null && amount.compareTo(SecurityDepositMethod.MAX_MONTHS) > 0) {
            throw new IllegalArgumentException(
                    "security_deposit_amount: " + amount + " months' rent is above the maximum of "
                            + SecurityDepositMethod.MAX_MONTHS
                            + ". For a flat dollar amount, set security_deposit_method to FLAT");
        }
    }

    @Transactional
    public boolean deleteTermsTemplate(UUID uuid) {
        return termsTemplateRepository.findById(uuid).map(existing -> {
            if (!termsTemplateRepository.softDelete(uuid)) {
                return false;
            }
            auditService.recordDelete("terms_template", uuid, AuditMapper.toMap(existing));
            return true;
        }).orElse(false);
    }

    /**
     * Brings a deleted terms template back, like an undo. A template that is not deleted is answered as the
     * template is, so a restore whose first answer was lost can be sent again. Empty for an unknown template.
     *
     * <p>Refused (409) when the template's place was taken while the template was deleted: the property now has
     * another template for the same kind of agreement, or another global template has the same name.
     * The restore is written to the audit log as an update of deletedAt (from the time back to empty).
     */
    @Transactional
    public Optional<TermsTemplate> restoreTermsTemplate(UUID uuid) {
        Optional<TermsTemplateRow> beforeOpt = termsTemplateRepository.findAnyById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        TermsTemplateRow before = beforeOpt.get();
        if (before.deletedAt() == null) {
            return Optional.of(before.toTermsTemplate());
        }

        if (before.property() != null) {
            if (termsTemplateRepository.findByPropertyAndAgreementType(before.property(), before.agreementType()).isPresent()) {
                throw RuleConflict.of("terms_template.type_taken", before.agreementType());
            }
        } else if (termsTemplateRepository.findGlobalByName(before.name()).isPresent()) {
            throw RuleConflict.of("terms_template.name_taken", before.name());
        }

        Optional<TermsTemplateRow> afterOpt = termsTemplateRepository.restore(uuid);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }
        AuditMapper.Diff diff = AuditMapper.diff(before, afterOpt.get());
        auditService.recordUpdate("terms_template", uuid, diff.before(), diff.after());
        return Optional.of(afterOpt.get().toTermsTemplate());
    }

    // A property may hold only one set per agreement type.
    private void requirePropertyScopeIsFree(UUID property, AgreementType agreementType) {
        if (termsTemplateRepository.findByPropertyAndAgreementType(property, agreementType).isPresent()) {
            throw new IllegalStateException(
                    "This property already has a terms template set for " + agreementType);
        }
    }

    // Globals may repeat an agreement type -- WA_Land_Lease and OR_Land_Lease -- so
    // the name is the identity.
    private void requireGlobalNameIsFree(String name) {
        if (termsTemplateRepository.findGlobalByName(name).isPresent()) {
            throw new IllegalStateException("A global template named " + name + " already exists");
        }
    }

    /**
     * An amount is only meaningful under the methods that carry one; every other
     * method requires it to be zero. Resolve the resulting pair from {@code before}
     * plus the patch, so patching either half alone still lands on a row the CHECK
     * constraints accept.
     *
     * <p>Which methods carry an amount is the pair's business, not a rule shared
     * across columns -- a deposit on MULTIPLE_OF_RENT carries a multiplier, a
     * utility on RUBS carries nothing.
     */
    private static void reconcileMethodAmountPairs(TermsTemplateRow before, Map<String, Object> changes) {
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

            if (method == null || !pair.methodsCarryingAmount().contains(method)) {
                changes.put(pair.amountColumn(), BigDecimal.ZERO);
                continue;
            }

            BigDecimal amount = amountTouched
                    ? toAmount(changes.get(pair.amountColumn()), pair.amountColumn())
                    : pair.currentAmount().apply(before);

            if (amount == null || amount.signum() <= 0) {
                throw new IllegalArgumentException(
                        pair.amountColumn() + " must be greater than zero when "
                                + pair.methodColumn() + " is " + method);
            }
            changes.put(pair.amountColumn(), amount);
        }
    }

    /**
     * Keeps late_fee_tiers in step with late_fee_method, and checks the chart.
     *
     * <p>Any method other than TIERED empties the tiers. Under TIERED the
     * chart must be complete right away, because a template has no draft
     * status: the CHECK constraints on terms_template always apply.
     *
     * <p>The chart is checked again when the due day or grace period changes,
     * because the first tier must start after the grace period ends.
     *
     * <p>Runs after {@link #reconcileMethodAmountPairs}, which has already
     * checked the method and upper-cased it.
     */
    private static void reconcileLateFeeTiers(TermsTemplateRow before, Map<String, Object> changes) {
        boolean touched = changes.containsKey("late_fee_method")
                || changes.containsKey("late_fee_tiers")
                || changes.containsKey("payment_due_day")
                || changes.containsKey("grace_period_days");
        if (!touched) {
            return;
        }

        String method = changes.containsKey("late_fee_method")
                ? (String) changes.get("late_fee_method")
                : nameOf(before.lateFeeMethod());

        if (!FeeMethod.TIERED.name().equals(method)) {
            if (changes.containsKey("late_fee_method") || changes.containsKey("late_fee_tiers")) {
                changes.put("late_fee_tiers", List.of());
            }
            return;
        }

        @SuppressWarnings("unchecked")
        List<LateFeeTier> tiers = changes.containsKey("late_fee_tiers")
                ? (List<LateFeeTier>) changes.get("late_fee_tiers")
                : before.lateFeeTiers();
        int paymentDueDay = toInt(changes.get("payment_due_day"), before.paymentDueDay());
        int gracePeriodDays = toInt(changes.get("grace_period_days"), before.gracePeriodDays());

        List<DomainProblem.Problem> problems =
                LateFeeTier.problems(FeeMethod.TIERED, tiers, paymentDueDay, gracePeriodDays);
        if (!problems.isEmpty()) {
            throw InvalidRequest.withDetails("late_fee_tier.chart_not_valid", problems);
        }
    }

    // The patched value when it is a whole number, otherwise the current one.
    // A value that is not a number is left for the database to refuse.
    private static int toInt(Object raw, int current) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return current;
            }
        }
        return current;
    }

    private static Map<String, Object> withoutHousekeeping(Map<String, Object> map) {
        Map<String, Object> copy = new LinkedHashMap<>(map);
        copy.keySet().removeAll(HOUSEKEEPING_KEYS);
        return copy;
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
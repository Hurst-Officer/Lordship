package io.github.lordship.termstemplate;

import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.FeeMethod;
import io.github.lordship.shared.SecurityDepositMethod;
import io.github.lordship.termstemplate.internal.TermsTemplateRepository;
import io.github.lordship.termstemplate.internal.TermsTemplateRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
            FeeMethod.NONE.name(), FeeMethod.FLAT.name(), FeeMethod.PERCENT_OF_RENT.name());

    private static final Set<String> NSF_FEE_METHODS = Set.of(
            FeeMethod.NONE.name(), FeeMethod.FLAT.name(), FeeMethod.BANK_OR_FLAT.name());

    private static final Set<String> VIOLATION_FEE_METHODS = Set.of(
            FeeMethod.NONE.name(), FeeMethod.FLAT.name());

    private static final Set<String> UTILITY_METHODS = Set.of("NONE", "FLAT", "RUBS", "SUBMETERED");

    private static final Set<String> TRASH_METHODS = Set.of("NONE", "FLAT", "RUBS");

    private static final Set<String> SECURITY_DEPOSIT_METHODS = Set.of("NONE", "FLAT", "MULTIPLE_OF_RENT");

    // if only these things change - DO NOT do an audit log
    private static final Set<String> HOUSEKEEPING_KEYS = Set.of("updatedAt");

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

        validateDepositCeiling(before, changes);
        reconcileMethodAmountPairs(before, changes);

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
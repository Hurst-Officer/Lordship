package io.github.lordship.securitydeposits;

import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.securitydeposits.internal.SecurityDepositRepository;
import io.github.lordship.securitydeposits.internal.SecurityDepositRow;
import io.github.lordship.tenancy.TenancyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Money held on behalf of a tenancy. Deliberately nowhere near
 * {@code transaction}: a deposit is a liability, not revenue, and posting one
 * to the account would credit the tenant's balance and stop them being billed.
 *
 * <p>There is no {@code settle} endpoint. Settling is done by PATCHing
 * {@code settledOn}, and clearing it un-settles -- the same call the tenancy
 * makes for {@code end_date}, for the same reason: it is one nullable date,
 * not a state machine.
 */
@Service
public class SecurityDepositService {

    private final SecurityDepositRepository securityDepositRepository;
    private final TenancyService tenancyService;
    private final AuditService auditService;
    private final AuditContext auditContext;

    public SecurityDepositService(SecurityDepositRepository securityDepositRepository,
                                  TenancyService tenancyService,
                                  AuditService auditService,
                                  AuditContext auditContext) {
        this.securityDepositRepository = securityDepositRepository;
        this.tenancyService = tenancyService;
        this.auditService = auditService;
        this.auditContext = auditContext;
    }

    public Optional<SecurityDeposit> findById(UUID uuid) {
        return securityDepositRepository.findById(uuid).map(SecurityDepositRow::toSecurityDeposit);
    }

    public List<SecurityDeposit> findByTenancy(UUID tenancy) {
        return securityDepositRepository.findByTenancy(tenancy).stream()
                .map(SecurityDepositRow::toSecurityDeposit)
                .toList();
    }

    /** What the park is still holding -- the rent roll figures. */
    public List<HeldDeposit> findHeldByProperty(UUID propertyId) {
        return securityDepositRepository.findHeldByProperty(propertyId);
    }

    /**
     * Still held, tenant already gone, oldest first. The report that says
     * which deposits are closest to costing money -- in lost deduction
     * rights, and eventually in unclaimed property.
     */
    public List<HeldDeposit> findAgingByProperty(UUID propertyId) {
        return securityDepositRepository.findAgingByProperty(propertyId);
    }

    /** Held and not yet given back, for comparing against what was agreed. */
    public BigDecimal sumHeldByTenancy(UUID tenancy) {
        return securityDepositRepository.sumHeldByTenancy(tenancy);
    }

    /**
     * Records money received under a lease or an assumption. Empty means the
     * tenancy does not exist.
     *
     * <p>MIGRATION is refused here by name: loading a deposit that predates
     * Lordship is a different act with a different permission, not a value in
     * a dropdown.
     */
    @Transactional
    public Optional<SecurityDeposit> create(UUID tenancy, BigDecimal amount, LocalDate collectedOn, SecurityDepositSource source) {
        if (!source.isAgentSelectable()) {
            throw new IllegalArgumentException(
                    "A " + source + " deposit is loaded through the migrations endpoint, not this one");
        }
        return insert(tenancy, amount, collectedOn, source);
    }

    /**
     * Loads a deposit that came across with an acquired park. No instrument,
     * and often no collection date -- the previous owner's records are what
     * they are.
     */
    @Transactional
    public Optional<SecurityDeposit> createMigrated(UUID tenancy, BigDecimal amount, LocalDate collectedOn) {
        return insert(tenancy, amount, collectedOn, SecurityDepositSource.MIGRATION);
    }

    @Transactional
    public Optional<SecurityDeposit> patchDeposit(UUID uuid, Map<String, Object> changes) {
        Optional<SecurityDepositRow> beforeOpt = securityDepositRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        SecurityDepositRow before = beforeOpt.get();

        Optional<SecurityDepositRow> afterOpt = securityDepositRepository.patch(uuid, coerce(changes));
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }
        SecurityDepositRow after = afterOpt.get();

        AuditMapper.Diff diff = AuditMapper.diff(before, after);
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("security_deposit", uuid, diff.before(), diff.after());
        }

        return Optional.of(after.toSecurityDeposit());
    }

    /**
     * For a row entered in error. A deposit that was genuinely given back is
     * settled, not deleted -- the money was real and the record should stay.
     */
    @Transactional
    public boolean deleteDeposit(UUID uuid) {
        return securityDepositRepository.findById(uuid).map(existing -> {
            if (!securityDepositRepository.softDelete(uuid)) {
                return false;
            }
            auditService.recordDelete("security_deposit", uuid, AuditMapper.toMap(existing));
            return true;
        }).orElse(false);
    }

    private Optional<SecurityDeposit> insert(UUID tenancy, BigDecimal amount, LocalDate collectedOn, SecurityDepositSource source) {
        if (tenancyService.findTenancyById(tenancy).isEmpty()) {
            return Optional.empty();
        }

        SecurityDepositRow saved = securityDepositRepository.save(
                SecurityDepositRow.forInsert(tenancy, amount, source, collectedOn, ActingAgent.resolve(auditContext)));

        auditService.recordInsert("security_deposit", saved.uuid(), AuditMapper.toMap(saved));
        return Optional.of(saved.toSecurityDeposit());
    }

    private static Map<String, Object> coerce(Map<String, Object> changes) {
        Map<String, Object> coerced = new LinkedHashMap<>(changes);
        coerced.replaceAll((column, value) -> switch (column) {
            case "collected_on", "settled_on" -> toDate(column, value);
            case "instrument" -> toUuid(column, value);
            case "amount" -> toAmount(column, value);
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
            throw new IllegalArgumentException(column + " must be a date as YYYY-MM-DD, not \"" + text + "\"");
        }
    }

    private static Object toUuid(String column, Object raw) {
        if (raw == null || raw instanceof UUID) {
            return raw;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(column + " must be a uuid, not \"" + text + "\"");
        }
    }

    private static Object toAmount(String column, Object raw) {
        if (raw == null || raw instanceof BigDecimal) {
            return raw;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " must be a number, not \"" + text + "\"");
        }
    }

}
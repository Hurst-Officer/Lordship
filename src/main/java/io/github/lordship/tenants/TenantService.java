package io.github.lordship.tenants;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancy.TenancyService;
import io.github.lordship.tenants.internal.TenantRepository;
import io.github.lordship.tenants.internal.TenantRow;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * A tenant row is one person's stay on one tenancy. A tenancy normally carries
 * several at once -- a household -- so adding one does nothing to the others.
 * The row that ends is the row of the person who left.
 */
@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final TenancyService tenancyService;
    private final AuditService auditService;

    public TenantService(
            TenantRepository tenantRepository,
            TenancyService tenancyService,
            AuditService auditService
    ) {
        this.tenantRepository = tenantRepository;
        this.tenancyService = tenancyService;
        this.auditService = auditService;
    }

    /** The billing-period guess. Same rule a tenancy uses: see TenancyService.billingPeriodStart. */
    static LocalDate defaultStartDate(LocalDate today) {
        return TenancyService.billingPeriodStart(today);
    }

    /**
     * The start date used when the office leaves it blank.
     *
     * <p>The first tenant ever added to a tenancy starts on the tenancy's start
     * date, because they moved in with it. Anyone added later (a spouse, a
     * roommate) gets the billing-period guess instead.
     */
    private LocalDate defaultStartFor(Tenancy tenancy) {
        boolean firstTenant = tenantRepository.findByTenancy(tenancy.uuid()).isEmpty();
        if (firstTenant && tenancy.startDate() != null) {
            return tenancy.startDate();
        }
        return defaultStartDate(LocalDate.now());
    }

    /**
     * Adds a person to a tenancy. The people already on it are untouched: a
     * spouse joining is not a move-out for anyone.
     *
     * <p>A person is on a tenancy once at a time, which is refused here for the
     * message and enforced by {@code uq_tenant_active_person} for the guarantee.
     * A person who moved out and moves back gets a second row rather than having
     * the first reopened, so the gap between the two stays visible.
     *
     * <p>An ended tenancy still admits a tenant: someone left off a household
     * that has since moved on is a correction the office has to be able to make.
     * A deleted or unknown tenancy does not.
     */
    // Note on design: http request records should not come into the service layer
    @Transactional
    public Tenant create(UUID tenancyId, UUID personId, LocalDate startDateOpt) {
        Tenancy tenancy = tenancyService.findTenancyById(tenancyId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Tenancy not found: " + tenancyId));

        tenantRepository.findActiveByTenancyAndPerson(tenancy.uuid(), personId)
                .ifPresent(existing -> {
                    throw new IllegalStateException(
                            "Person " + personId + " is already an active tenant on tenancy "
                                    + tenancy.uuid() + " (tenant " + existing.uuid() + ")");
                });

        LocalDate startDate = startDateOpt != null
                ? startDateOpt
                : defaultStartFor(tenancy);

        TenantRow row = tenantRepository.save(tenancy.uuid(), personId, startDate);

        auditService.recordInsert("tenant", row.uuid(), AuditMapper.toMap(row));
        return row.toTenant();
    }

    public Optional<Tenant> findById(UUID uuid) {
        return tenantRepository.findById(uuid).map(TenantRow::toTenant);
    }

    /** Who is on this tenancy now. */
    public List<Tenant> findActiveByTenancy(UUID tenancyId) {
        return tenantRepository.findActiveByTenancy(tenancyId)
                .stream()
                .map(TenantRow::toTenant)
                .toList();
    }

    /** Every stay on this tenancy, ended ones included. */
    public List<Tenant> findByTenancy(UUID tenancyId) {
        return tenantRepository.findByTenancy(tenancyId)
                .stream()
                .map(TenantRow::toTenant)
                .toList();
    }

    /** Every tenancy this person has been on. */
    public List<Tenant> findByPerson(UUID personId) {
        return tenantRepository.findByPerson(personId)
                .stream()
                .map(TenantRow::toTenant)
                .toList();
    }


    static void normalize(Map<String, Object> changes, String key, LocalDate current) {
        if (!changes.containsKey(key)) {
            return;
        }

        Object raw = changes.get(key);
        LocalDate wanted;

        if (raw == null || (raw instanceof String blank && blank.isBlank())) {
            wanted = null;
        } else if (raw instanceof String text) {
            try {
                wanted = LocalDate.parse(text);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid date for " + key, e);
            }
        } else {
            throw new IllegalArgumentException("Invalid date for " + key + ": expected text or null");
        }

        if (Objects.equals(current, wanted)) {
            changes.remove(key);
        } else {
            changes.put(key, wanted);
        }
    }


    @Transactional
    public Optional<Tenant> patchTenant(UUID uuid, Map<String, Object> changes) {
        Optional<TenantRow> beforeOpt = tenantRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }

        TenantRow before = beforeOpt.get();
        // Mutable copy so the no-op keys can be dropped before the write.
        Map<String, Object> mutable = new HashMap<>(changes);

        normalize(mutable, "start_date", before.startDate());
        normalize(mutable, "end_date", before.endDate());

        if (mutable.isEmpty()) {
            return Optional.of(before.toTenant());
        }

        // A key that survived the normalising above carries a real change; one that
        // did not means the supplied value already matched, so `before` is the
        // effective value either way.
        LocalDate startAfter = mutable.containsKey("start_date")
                ? (LocalDate) mutable.get("start_date")
                : before.startDate();
        LocalDate endAfter = mutable.containsKey("end_date")
                ? (LocalDate) mutable.get("end_date")
                : before.endDate();

        if (startAfter != null && endAfter != null && endAfter.isBefore(startAfter)) {
            throw new IllegalArgumentException(
                    "endDate " + endAfter + " cannot be before startDate " + startAfter);
        }

        boolean reopening = before.endDate() != null && endAfter == null;
        if (reopening) {
            tenantRepository.findActiveByTenancyAndPerson(before.tenancyId(), before.personId())
                    .filter(other -> !Objects.equals(other.uuid(), uuid))
                    .ifPresent(other -> {
                        throw new IllegalStateException(
                                "Cannot clear the end date on tenant " + uuid
                                        + ": that person is already active on this tenancy as "
                                        + other.uuid());
                    });
        }

        Optional<TenantRow> afterOpt = tenantRepository.patch(uuid, mutable);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }

        TenantRow after = afterOpt.get();

        var diff = AuditMapper.diff(before, after);
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("tenant", uuid, diff.before(), diff.after());
        }

        return Optional.of(after.toTenant());
    }

    /**
     * Removing the row, for a tenant added to the wrong tenancy. Someone who
     * genuinely left gets an end_date through patch instead -- a soft delete
     * takes the stay out of the record, and a stay that happened should stay in.
     */
    @Transactional
    public boolean softDelete(UUID uuid) {
        Optional<TenantRow> existing = tenantRepository.findById(uuid);
        if (existing.isEmpty() || !tenantRepository.softDelete(uuid)) {
            return false;
        }

        auditService.recordDelete("tenant", uuid, AuditMapper.toMap(existing.get()));
        return true;
    }
}
package io.github.lordship.tenants;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancy.TenancyService;
import io.github.lordship.tenants.internal.OccupantRepository;
import io.github.lordship.tenants.internal.OccupantRow;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * An occupant row is one person living in a home without being on the lease.
 * Structurally a tenant row; deliberately not one, because nothing here may
 * reach an invoice, a signature block or {@code tenancy.tenant_names}.
 *
 * <p>Nothing stops a person being an active tenant AND an active occupant on
 * the same tenancy. That overlap is the workflow, not a mistake: correcting
 * someone who was recorded as a tenant means adding them as an occupant first
 * and removing the tenant row second, so both rows exist in between.
 */
@Service
public class OccupantService {

    private final OccupantRepository occupantRepository;
    private final TenancyService tenancyService;
    private final AuditService auditService;

    public OccupantService(
            OccupantRepository occupantRepository,
            TenancyService tenancyService,
            AuditService auditService
    ) {
        this.occupantRepository = occupantRepository;
        this.tenancyService = tenancyService;
        this.auditService = auditService;
    }

    /**
     * Adds a person to a home without adding them to the lease.
     *
     * <p>A person is an occupant of a tenancy once at a time, refused here for
     * the message and enforced by {@code uq_occupant_active_person} for the
     * guarantee. Someone who leaves and comes back gets a second row, so the
     * gap between the two stays visible.
     *
     * <p>An omitted start date takes {@link TenantService#defaultStartDate},
     * so a household entered in one sitting shares one date rather than
     * splitting across a month boundary for no reason.
     */
    // Note on design: http request records should not come into the service layer
    @Transactional
    public Occupant create(UUID tenancyId, UUID personId, LocalDate startDateOpt) {
        Tenancy tenancy = tenancyService.findTenancyById(tenancyId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Tenancy not found: " + tenancyId));

        occupantRepository.findActiveByTenancyAndPerson(tenancy.uuid(), personId)
                .ifPresent(existing -> {
                    throw new IllegalStateException(
                            "Person " + personId + " is already an active occupant on tenancy "
                                    + tenancy.uuid() + " (occupant " + existing.uuid() + ")");
                });

        LocalDate startDate = startDateOpt != null
                ? startDateOpt
                : TenantService.defaultStartDate(LocalDate.now());

        OccupantRow row = occupantRepository.save(tenancy.uuid(), personId, startDate);

        auditService.recordInsert("occupant", row.uuid(), AuditMapper.toMap(row));
        return row.toOccupant();
    }

    public Optional<Occupant> findById(UUID uuid) {
        return occupantRepository.findById(uuid).map(OccupantRow::toOccupant);
    }

    /** Who is in this home now. */
    public List<Occupant> findActiveByTenancy(UUID tenancyId) {
        return occupantRepository.findActiveByTenancy(tenancyId)
                .stream()
                .map(OccupantRow::toOccupant)
                .toList();
    }

    /** Everyone who has lived here, past stays included. */
    public List<Occupant> findByTenancy(UUID tenancyId) {
        return occupantRepository.findByTenancy(tenancyId)
                .stream()
                .map(OccupantRow::toOccupant)
                .toList();
    }

    /** Everywhere this person has lived. */
    public List<Occupant> findByPerson(UUID personId) {
        return occupantRepository.findByPerson(personId)
                .stream()
                .map(OccupantRow::toOccupant)
                .toList();
    }

// Drop-in replacement for patchOccupant() in OccupantService, plus the new private
// helper. Nothing else in the class changes; the imports you already have
// (LocalDate, DateTimeParseException, java.util.*) cover it.

    /**
     * Turns one date key of a patch into something the repository can write, or
     * drops it. Text is parsed; null and blank text clear the column; anything
     * else (a number, a boolean, an already-parsed object) is refused rather than
     * mistaken for a clear. A value equal to the current one is dropped as a no-op.
     */
    private static void normalizeDateChange(Map<String, Object> changes, String key, LocalDate current) {
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
    public Optional<Occupant> patchOccupant(UUID uuid, Map<String, Object> changes) {
        Optional<OccupantRow> beforeOpt = occupantRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }

        OccupantRow before = beforeOpt.get();
        // Mutable copy so the no-op keys can be dropped before the write.
        Map<String, Object> mutable = new HashMap<>(changes);

        normalizeDateChange(mutable, "start_date", before.startDate());
        normalizeDateChange(mutable, "end_date", before.endDate());

        if (mutable.isEmpty()) {
            return Optional.of(before.toOccupant());
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
            occupantRepository.findActiveByTenancyAndPerson(before.tenancyId(), before.personId())
                    .filter(other -> !Objects.equals(other.uuid(), uuid))
                    .ifPresent(other -> {
                        throw new IllegalStateException(
                                "Cannot clear the end date on occupant " + uuid
                                        + ": that person is already active on this tenancy as "
                                        + other.uuid());
                    });
        }

        Optional<OccupantRow> afterOpt = occupantRepository.patch(uuid, mutable);
        if (afterOpt.isEmpty()) return Optional.empty();

        OccupantRow after = afterOpt.get();

        var diff = AuditMapper.diff(before, after);
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("occupant", uuid, diff.before(), diff.after());
        }

        return Optional.of(after.toOccupant());
    }

    /**
     * Removing the row, for an occupant added to the wrong tenancy. Someone who
     * genuinely left gets an end_date through patch instead -- a soft delete
     * takes the stay out of the record, and a stay that happened should stay in.
     */
    @Transactional
    public boolean softDelete(UUID uuid) {
        return occupantRepository.findById(uuid).map(occupant -> {
            if (!occupantRepository.softDelete(uuid)) {
                return false;
            }
            auditService.recordDelete("occupant", uuid, AuditMapper.toMap(occupant));
            return true;
        }).orElse(false);
    }
}
package io.github.lordship.tenants;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.tenancy.Tenancy;
import io.github.lordship.tenancy.TenancyService;
import io.github.lordship.tenants.internal.InterestedPartyRepository;
import io.github.lordship.tenants.internal.InterestedPartyRow;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class InterestedPartyService {

    private final InterestedPartyRepository interestedPartyRepository;
    private final TenancyService tenancyService;
    private final AuditService auditService;

    public InterestedPartyService(
            InterestedPartyRepository interestedPartyRepository,
            TenancyService tenancyService,
            AuditService auditService) {
        this.interestedPartyRepository = interestedPartyRepository;
        this.tenancyService = tenancyService;
        this.auditService = auditService;
    }

    // Note on design: http request records should not come into the service layer
    @Transactional
    public InterestedParty create(UUID tenancyId, UUID personId, LocalDate startDateOpt) {
        Tenancy tenancy = tenancyService.findTenancyById(tenancyId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Tenancy not found: " + tenancyId));

        LocalDate startDate = startDateOpt != null
                ? startDateOpt
                : TenantService.defaultStartDate(LocalDate.now());

        InterestedPartyRow row = interestedPartyRepository.save(tenancy.uuid(), personId, startDate);

        auditService.recordInsert("tenancy_interested_party", row.uuid(), AuditMapper.toMap(row));
        return row.toInterestedParty();
    }

    public Optional<InterestedParty> findById(UUID uuid) {
        return interestedPartyRepository.findById(uuid).map(InterestedPartyRow::toInterestedParty);
    }

    /** Who has an interest in this home now */
    public List<InterestedParty> findActiveByTenancy(UUID tenancyId) {
        return interestedPartyRepository.findActiveByTenancy(tenancyId)
                .stream()
                .map(InterestedPartyRow::toInterestedParty)
                .toList();
    }

    /** Everyone who has had an interest here, past parties included. */
    public List<InterestedParty> findByTenancy(UUID tenancyId) {
        return interestedPartyRepository.findByTenancy(tenancyId)
                .stream()
                .map(InterestedPartyRow::toInterestedParty)
                .toList();
    }

    /** Everywhere this person has had an interest in a tenancy. */
    public List<InterestedParty> findByPerson(UUID personId) {
        return interestedPartyRepository.findByPerson(personId)
                .stream()
                .map(InterestedPartyRow::toInterestedParty)
                .toList();
    }


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
    public Optional<InterestedParty> patchInterestedParty(UUID uuid, Map<String, Object> changes) {
        Optional<InterestedPartyRow> beforeOpt = interestedPartyRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }

        InterestedPartyRow before = beforeOpt.get();
        // Mutable copy so the no-op keys can be dropped before the write.
        Map<String, Object> mutable = new HashMap<>(changes);

        normalizeDateChange(mutable, "start_date", before.startDate());
        normalizeDateChange(mutable, "end_date", before.endDate());

        if (mutable.isEmpty()) {
            return Optional.of(before.toInterestedParty());
        }

        // A key that survived the normalising above carries a real change;
        // one that did not means the supplied value already matched, so `before` is the
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

        // Clearing an end_date reopens this interested party. Refuse if that person
        // is already active on the tenancy via a different row — otherwise we'd
        // silently create two active records for the same person/tenancy.
        boolean reopening = before.endDate() != null && endAfter == null;
        if (reopening) {
            interestedPartyRepository.findActiveByTenancyAndPerson(before.tenancyId(), before.personId())
                    .filter(other -> !Objects.equals(other.uuid(), uuid))
                    .ifPresent(other -> {
                        throw new IllegalStateException(
                                "Cannot clear the end date on interested party " + uuid
                                        + ": that person is already active on this tenancy as "
                                        + other.uuid());
                    });
        }

        Optional<InterestedPartyRow> afterOpt = interestedPartyRepository.patch(uuid, mutable);
        if (afterOpt.isEmpty()) return Optional.empty();

        InterestedPartyRow after = afterOpt.get();

        var diff = AuditMapper.diff(before, after);
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate("tenancy_interested_party", uuid, diff.before(), diff.after());
        }

        return Optional.of(after.toInterestedParty());
    }

    @Transactional
    public boolean softDelete(UUID uuid) {
        return interestedPartyRepository.findById(uuid).map(interestedPartyRow -> {
            if (!interestedPartyRepository.softDelete(uuid)) {
                return false;
            }
            auditService.recordDelete("tenancy_interested_party", uuid, AuditMapper.toMap(interestedPartyRow));
            return true;
        }).orElse(false);
    }

}

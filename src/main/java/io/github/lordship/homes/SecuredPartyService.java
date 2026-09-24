package io.github.lordship.homes;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.homes.internal.SecuredPartyRepository;
import io.github.lordship.homes.internal.SecuredPartyRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class SecuredPartyService {

    private static final String TABLE = "mobile_home_secured_party";

    private final SecuredPartyRepository securedPartyRepository;
    private final AuditService auditService;

    public SecuredPartyService(SecuredPartyRepository securedPartyRepository, AuditService auditService) {
        this.securedPartyRepository = securedPartyRepository;
        this.auditService = auditService;
    }

    // A person has one active claim on a home at a time, refused here for the
    // message and enforced by uq_secured_party_active_person for the guarantee --
    // same split as TenantService.create() / InterestedPartyService.create().
    //
    // Unlike Tenant/InterestedParty, an omitted start date here defaults to today
    // rather than the next billing period: a secured party is a legal claim, not
    // a rent-paying stay, so there is no billing-period rounding to apply.
    @Transactional
    public SecuredParty createSecuredParty(UUID mobileHomeId, UUID personId, LocalDate startDateOpt) {
        securedPartyRepository.findActiveByHomeAndPerson(mobileHomeId, personId)
                .ifPresent(existing -> {
                    throw new IllegalStateException(
                            "Person " + personId + " is already an active secured party on mobile home "
                                    + mobileHomeId + " (secured party " + existing.uuid() + ")");
                });

        LocalDate startDate = startDateOpt != null ? startDateOpt : LocalDate.now();

        SecuredPartyRow row = securedPartyRepository.save(mobileHomeId, personId, startDate)
                .orElseThrow(() -> new IllegalArgumentException("No mobile home " + mobileHomeId));

        auditService.recordInsert(TABLE, row.uuid(), AuditMapper.toMap(row));
        return row.toSecuredParty();
    }

    public Optional<SecuredParty> findById(UUID uuid) {
        return securedPartyRepository.findById(uuid).map(SecuredPartyRow::toSecuredParty);
    }

    /** Who currently has a claim on this home. */
    public List<SecuredParty> findActiveByHome(UUID mobileHomeId) {
        return securedPartyRepository.findActiveByHome(mobileHomeId)
                .stream()
                .map(SecuredPartyRow::toSecuredParty)
                .toList();
    }

    /** Every claim this home has carried, past ones included. */
    public List<SecuredParty> findByHome(UUID mobileHomeId) {
        return securedPartyRepository.findByHome(mobileHomeId)
                .stream()
                .map(SecuredPartyRow::toSecuredParty)
                .toList();
    }

    /** Everywhere this person has held a claim. */
    public List<SecuredParty> findByPerson(UUID personId) {
        return securedPartyRepository.findByPerson(personId)
                .stream()
                .map(SecuredPartyRow::toSecuredParty)
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
    public Optional<SecuredParty> patchSecuredParty(UUID uuid, Map<String, Object> changes) {
        Optional<SecuredPartyRow> beforeOpt = securedPartyRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }

        SecuredPartyRow before = beforeOpt.get();
        // Mutable copy so the no-op keys can be dropped before the write.
        Map<String, Object> mutable = new HashMap<>(changes);

        normalizeDateChange(mutable, "start_date", before.startDate());
        normalizeDateChange(mutable, "end_date", before.endDate());

        if (mutable.isEmpty()) {
            return Optional.of(before.toSecuredParty());
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

        // Clearing an end_date reopens this claim. Refuse if that person is already
        // active on the home via a different row -- otherwise we'd silently create
        // two active claims for the same person/home.
        boolean reopening = before.endDate() != null && endAfter == null;
        if (reopening) {
            securedPartyRepository.findActiveByHomeAndPerson(before.mobileHomeId(), before.personId())
                    .filter(other -> !Objects.equals(other.uuid(), uuid))
                    .ifPresent(other -> {
                        throw new IllegalStateException(
                                "Cannot clear the end date on secured party " + uuid
                                        + ": that person is already active on this home as "
                                        + other.uuid());
                    });
        }

        Optional<SecuredPartyRow> afterOpt = securedPartyRepository.patch(uuid, mutable);
        if (afterOpt.isEmpty()) return Optional.empty();

        SecuredPartyRow after = afterOpt.get();

        var diff = AuditMapper.diff(before, after);
        if (!diff.before().isEmpty()) {
            auditService.recordUpdate(TABLE, uuid, diff.before(), diff.after());
        }

        return Optional.of(after.toSecuredParty());
    }

    @Transactional
    public boolean deleteSecuredParty(UUID uuid) {
        return securedPartyRepository.findById(uuid).map(securedPartyRow -> {
            if (!securedPartyRepository.softDelete(uuid)) {
                return false;
            }
            auditService.recordDelete(TABLE, uuid, AuditMapper.toMap(securedPartyRow));
            return true;
        }).orElse(false);
    }
}

package io.github.lordship.tenancy;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.tenancy.internal.MoveInRepository;
import io.github.lordship.tenancy.internal.MoveInRepository.LineRow;
import io.github.lordship.tenancy.internal.MoveInRepository.MoveInRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Move-In (Glade): the steps from "these people will become tenants" to a tenancy that belongs to the rent roll.
 * Under the hood a move-in is a tenancy whose move_in_started_at is set and whose move_in_done_at is not yet.
 *
 * <p>A tenancy is marked as a move-in when Glade opens the tenancy with its household (tenants Household.open,
 * behind POST /api/tenancy/open and /onboard). A tenancy that came another way (the data migration, POST
 * /api/tenancy/create) is not a move-in and never shows in Move-In.
 * Where a move-in stands is worked out every time ({@link MoveInStage#of}). Finishing a move-in puts the lease's
 * rent in force as well, so the instruments module does it (MoveInFinish), and calls {@link #markDone} here.
 *
 * <p>Every write is recorded in the audit log under the tenancy, with the camelCase column names.
 */
@Service
public class MoveIns {

    // the unit separator the tenants' names are joined with in MoveInRepository
    private static final String NAME_SEPARATOR = String.valueOf((char) 31);

    private final MoveInRepository repository;
    private final AuditService auditService;

    public MoveIns(MoveInRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    /** A tenancy's Move-In facts. Empty for an unknown or deleted tenancy, and for one that is not a move-in. */
    public Optional<MoveIn> find(UUID tenancyId) {
        return repository.findFacts(tenancyId)
                .filter(row -> row.moveInStartedAt() != null)
                .map(MoveInRow::toMoveIn);
    }

    /**
     * Changes some Move-In facts. request is the PATCH body: moveInSigningOn and moveInSignedOn (a date as
     * YYYY-MM-DD, or null to clear the date) and moveInCopyGiven (true or false). Other names are ignored.
     *
     * <p>Empty for an unknown or deleted tenancy. 409 move_in.not_moving_in for a tenancy that did not come
     * through Move-In, 409 move_in.finished for a finished move-in. 400 tenancy.date_invalid for a date that
     * cannot be read, 400 move_in.yes_or_no for a copy-given that is not true or false.
     * Sending the values the move-in already has changes nothing and is answered with the move-in.
     */
    @Transactional
    public Optional<MoveIn> patch(UUID tenancyId, Map<String, Object> request) {
        Optional<MoveInRow> found = repository.findFacts(tenancyId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        MoveInRow before = found.get();
        if (before.moveInStartedAt() == null) {
            throw RuleConflict.of("move_in.not_moving_in");
        }
        if (before.moveInDoneAt() != null) {
            throw RuleConflict.of("move_in.finished");
        }

        Map<String, Object> changes = new LinkedHashMap<>();
        for (String name : MoveInRepository.patchable()) {
            if (!request.containsKey(name)) {
                continue;
            }
            Object raw = request.get(name);
            if (name.equals("moveInCopyGiven")) {
                if (!(raw instanceof Boolean given)) {
                    throw InvalidRequest.onField(name, "move_in.yes_or_no");
                }
                changes.put(name, given);
            } else {
                changes.put(name, dateOf(name, raw));
            }
        }
        if (changes.isEmpty()) {
            return Optional.of(before.toMoveIn());
        }

        MoveInRow after = repository.update(tenancyId, changes)
                // finished, deleted or taken out of Move-In between the read and this write
                .orElseThrow(() -> RuleConflict.of("move_in.finished"));
        AuditMapper.Diff diff = AuditMapper.diff(before, after);
        if (!diff.after().isEmpty()) {
            auditService.recordUpdate("tenancy", tenancyId, diff.before(), diff.after());
        }
        return Optional.of(after.toMoveIn());
    }

    /**
     * Marks a tenancy just opened through Move-In. Called by Household.open in the transaction that opens the
     * tenancy. A tenancy already marked is left as the tenancy is.
     */
    @Transactional
    public void markStarted(UUID tenancyId) {
        repository.markStarted(tenancyId).ifPresent(after -> {
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("moveInStartedAt", null);
            Map<String, Object> now = new LinkedHashMap<>();
            now.put("moveInStartedAt", after.moveInStartedAt());
            auditService.recordUpdate("tenancy", tenancyId, before, now);
        });
    }

    /**
     * Stamps a move-in finished, by agentId. Empty when the tenancy is gone, is not a move-in, or was already
     * finished: the caller (MoveInFinish) has checked the stage, and answers a second finish as the first.
     */
    @Transactional
    public Optional<MoveIn> markDone(UUID tenancyId, UUID agentId) {
        Optional<MoveInRow> before = repository.findFacts(tenancyId);
        return repository.markDone(tenancyId, agentId).map(after -> {
            before.ifPresent(row -> {
                AuditMapper.Diff diff = AuditMapper.diff(row, after);
                auditService.recordUpdate("tenancy", tenancyId, diff.before(), diff.after());
            });
            return after.toMoveIn();
        });
    }

    /** One tenancy's move-in as a row of the lists, deleted or not. Empty when the tenancy is not a move-in. */
    public Optional<MoveInLine> line(UUID tenancyId) {
        return repository.findLine(tenancyId).map(MoveIns::line);
    }

    /** Every move-in in process, on every live property, the oldest first. The caller keeps to the agent's scope. */
    public List<MoveInLine> inProcess() {
        return repository.findInProcess().stream().map(MoveIns::line).toList();
    }

    /** A property's move-ins in process and those finished in the last 90 days, the oldest first. */
    public List<MoveInLine> ofProperty(UUID propertyId) {
        return repository.findByProperty(propertyId).stream().map(MoveIns::line).toList();
    }

    /** Every move-in a lot has had, deleted or finished or not, the oldest first. */
    public List<MoveInLine> ofLot(UUID lotId) {
        return repository.findByLot(lotId).stream().map(MoveIns::line).toList();
    }

    // ---- internals -------------------------------------------------------------------------------

    private static MoveInLine line(LineRow row) {
        MoveInStage.Facts facts = new MoveInStage.Facts(row.tenantCount(), row.leaseStatus(),
                row.moveInSigningOn() != null, row.moveInSignedOn() != null, row.moveInCopyGiven(),
                row.scanUploadedAt() != null, row.pagesSettled(), row.checksFailed(), row.requestsOpen(),
                row.moveInDoneAt() != null);
        MoveInStage stage = MoveInStage.of(facts);
        List<String> tenants = row.tenantNames() == null
                ? List.of()
                : Arrays.stream(row.tenantNames().split(NAME_SEPARATOR)).filter(name -> !name.isBlank()).toList();
        return new MoveInLine(row.tenancyId(), row.lotId(), row.propertyId(), row.propertyCode(), row.lotNumber(),
                row.startDate(), tenants, row.tenantCount(), row.leaseId(), row.leaseStatus(), row.leaseSerial(),
                row.leaseGeneratedAt(), row.agreementType(), row.rentAccepted(), row.moveInStartedAt(),
                row.moveInSigningOn(), row.moveInSignedOn(), row.moveInCopyGiven(), row.moveInDoneAt(),
                row.deletedAt(), row.scanUploadedAt(), row.checksFailed(), row.requestsOpen(), stage,
                MoveInStage.waitingOn(stage, facts), stageSince(stage, row));
    }

    // The day the move-in reached its stage, as far as the facts tell (see MoveInLine.stageSince)
    private static LocalDate stageSince(MoveInStage stage, LineRow row) {
        return switch (stage) {
            case HOUSEHOLD, LEASE -> MoveInLine.dayOf(row.moveInStartedAt());
            case APPOINTMENT -> row.leaseGeneratedAt() != null
                    ? MoveInLine.dayOf(row.leaseGeneratedAt())
                    : MoveInLine.dayOf(row.moveInStartedAt());
            case SIGNING -> row.moveInSigningOn();
            case REVIEW -> later(row.moveInSignedOn(), MoveInLine.dayOf(row.scanUploadedAt()));
            case MOVED_IN -> MoveInLine.dayOf(row.moveInDoneAt());
        };
    }

    // The later of two days; either may be missing
    private static LocalDate later(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    // A date sent in a PATCH: null or blank clears the date
    private static LocalDate dateOf(String field, Object raw) {
        if (raw == null || raw.toString().isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.toString().trim());
        } catch (DateTimeParseException e) {
            throw InvalidRequest.onField(field, "tenancy.date_invalid", raw);
        }
    }
}

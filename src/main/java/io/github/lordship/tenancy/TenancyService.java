package io.github.lordship.tenancy;


import io.github.lordship.accounts.AccountService;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.lots.Lot;
import io.github.lordship.lots.LotService;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.tenancy.internal.TenancyRepository;
import io.github.lordship.tenancy.internal.TenancyRow;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class TenancyService {
    private final TenancyRepository tenancyRepository;
    private final AuditService auditService;
    private final AccountService accountService;
    private final LotService lotService;

    private static final Logger log = LoggerFactory.getLogger(TenancyService.class);

    public TenancyService(
            TenancyRepository tenancyRepository,
            AuditService auditService,
            AccountService accountService,
            LotService lotService
    ) {
        this.tenancyRepository = tenancyRepository;
        this.auditService = auditService;
        this.accountService = accountService;
        this.lotService = lotService;
    }

    /**
     * The start date used when the office leaves it blank. The office is
     * usually setting up next month, not today. Before the 10th, that is the
     * 1st of this month. From the 10th on, it is the 1st of next month.
     */
    public static LocalDate billingPeriodStart(LocalDate today) {
        return today.getDayOfMonth() < 10
                ? today.withDayOfMonth(1)
                : today.plusMonths(1).withDayOfMonth(1);
    }

    /** Creates a tenancy with a guessed start date. See {@link #create(UUID, LocalDate)}. */
    @Transactional
    public Tenancy create(UUID lotId) {
        return create(lotId, null);
    }

    /**
     * startDate is when the household takes possession. If it is null, the
     * billing-period guess is used (see {@link #billingPeriodStart}). The
     * office can change it later with a PATCH.
     *
     * <p>A lot admits a new tenancy only when it is rentable, and only if no
     * month would end up with three tenancies (see {@link #requireRoomOnLot}).
     *
     * <p>{@code is_rentable} governs new tenancies only. A lot that becomes
     * flooded, condemned or held for a road widening keeps the tenants already
     * on it -- same reasoning as revoking a permissible agreement type not
     * invalidating a charge term already signed and served.
     *
     * <p>{@code LotService.findById} filters soft-deleted lots, so this also
     * refuses a tenancy on a lot that was deleted.
     */
    @Transactional
    public Tenancy create(UUID lotId, LocalDate startDate) {
        Lot lot = lotService.findById(lotId)
                .orElseThrow(() -> new EntityNotFoundException("Lot not found: " + lotId));

        if (Boolean.FALSE.equals(lot.isRentable())) {
            throw RuleConflict.of("tenancy.lot_not_rentable", lot.lotNumber(), reasonOf(lot));
        }

        LocalDate start = (startDate != null) ? startDate : billingPeriodStart(LocalDate.now());
        requireRoomOnLot(lotId, null, start, null);

        TenancyRow row = tenancyRepository.save(lotId, start);
        Tenancy tenancy = row.toTenancy();
        accountService.createAccount(tenancy.uuid(), null);

        // log
        auditService.recordInsert("tenancy", row.uuid(), AuditMapper.toMap(row));
        return tenancy;
    }

    /**
     * Starts a tenancy the way Glade's "New tenancy" does: on a lot that is open for the kind of agreement picked.
     *
     * <p>Three things are asked of the lot. The lot is rentable. The lot offers the kind
     * (lot_permissible_agreement_type, what Rates and deals edits). And fewer than two tenancies hold the lot on
     * the start date: a tenancy holds the lot until the tenancy's end date, and while the tenancy has none.
     *
     * <p>One tenancy already on the lot is allowed on purpose, end date or not. A household that is wrapping up
     * rarely has an exact last day yet, and the office has to be able to set up the next household meanwhile.
     * The two tenancies overlap until the first is given an end date. Two is the ceiling, the same ceiling the
     * database keeps (tenancy_active_limit).
     *
     * <p>A STORAGE or UTILITY_SERVICE agreement never shares a lot with a land lease, overlap or not: refused
     * when a tenancy that holds the lot has a charge term of the other kind. A tenancy with no charge term yet
     * has no kind, and rules nothing out.
     *
     * <p>The kind is checked here and not stored: a tenancy has no kind of the tenancy's own. The kind lives on
     * the tenancy's charge terms, which the lease creates.
     *
     * <p>A blank start date becomes the billing period the office is working in ({@link #billingPeriodStart}).
     *
     * <p>Throws InvalidRequest (400) lot.unknown or tenancy.kind_needed, and RuleConflict (409)
     * tenancy.lot_not_rentable, lot.does_not_permit, tenancy.lot_full or tenancy.kind_conflict.
     */
    @Transactional
    public Tenancy openOn(UUID lotId, AgreementType agreementType, LocalDate startDate) {
        Lot lot = lotService.findById(lotId)
                .orElseThrow(() -> InvalidRequest.onField("lotId", "lot.unknown", lotId));
        if (agreementType == null) {
            throw InvalidRequest.onField("agreementType", "tenancy.kind_needed");
        }
        if (Boolean.FALSE.equals(lot.isRentable())) {
            throw RuleConflict.of("tenancy.lot_not_rentable", lot.lotNumber(), reasonOf(lot));
        }
        if (!lot.permits(agreementType)) {
            throw RuleConflict.of("lot.does_not_permit", lot.lotNumber(), agreementType);
        }

        LocalDate start = (startDate != null) ? startDate : billingPeriodStart(LocalDate.now());
        if (tenancyRepository.countHolding(lotId, null, start) >= 2) {
            throw RuleConflict.of("tenancy.lot_full", lot.lotNumber());
        }
        List<String> never = kindsNeverBeside(agreementType);
        if (!never.isEmpty() && tenancyRepository.holdsKind(lotId, never, start)) {
            throw RuleConflict.of("tenancy.kind_conflict", lot.lotNumber(), agreementType);
        }
        return create(lotId, start);
    }

    // The kinds of agreement that never share a lot with this kind: storage and utility service on one side,
    // a land lease on the other. Empty for a kind that may sit beside anything.
    private static List<String> kindsNeverBeside(AgreementType agreementType) {
        return switch (agreementType) {
            case LAND -> List.of(AgreementType.STORAGE.name(), AgreementType.UTILITY_SERVICE.name());
            case STORAGE, UTILITY_SERVICE -> List.of(AgreementType.LAND.name());
            default -> List.of();
        };
    }

    // Why a lot is not rentable, for a refusal. A lot marked not rentable without a reason says so.
    private static String reasonOf(Lot lot) {
        String reason = lot.notRentableReason();
        return (reason == null || reason.isBlank()) ? "-" : reason;
    }

    public Optional<Tenancy> findTenancyById(UUID uuid) {
        return tenancyRepository.findById(uuid).map(TenancyRow::toTenancy);
    }

    public List<Tenancy> findActiveTenancyByLot(UUID lotId) {
        return tenancyRepository.findActiveByLot(lotId)
                .stream()
                .map(TenancyRow::toTenancy)
                .toList();
    }

    /**
     * The one door onto a tenancy's dates. There is no separate close endpoint:
     * ending a tenancy is setting its end_date, so it goes through here with
     * everything else.
     *
     * <p>end_date is a state transition, not just a column. Null to a date
     * closes the tenancy; date to a different date corrects a figure someone
     * typed wrong; a date back to null reopens it, which is refused when the
     * lot already carries its two active tenancies. Without that last check a
     * reopen is a third way onto a full lot, since the create path never sees
     * it.
     *
     * <p>Mutable hashmap so the no-op keys can be dropped before the write.
     */
    @Transactional
    public Optional<Tenancy> patchTenancy(UUID uuid, Map<String, Object> changes) {
        Optional<TenancyRow> beforeOpt = tenancyRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        TenancyRow before = beforeOpt.get();

        Map<String, Object> mutable = new HashMap<>(changes);

        if (mutable.containsKey("start_date")) {
            Object raw = mutable.get("start_date");
            try {
                if (raw instanceof String s && !s.isBlank()) {
                    LocalDate parsed = LocalDate.parse(s);

                    if (Objects.equals(before.startDate(), parsed)) {
                        mutable.remove("start_date");
                    } else {
                        mutable.put("start_date", parsed);
                    }

                } else {
                    // Will skip if not edited
                    if (before.startDate() == null) {
                        mutable.remove("start_date");
                    } else {
                        mutable.put("start_date", null);
                    }
                }
            } catch(DateTimeParseException e) {
                throw InvalidRequest.onField("startDate", "tenancy.date_invalid", raw); // Throws error if updated date is not valid
            }
        }

        if (mutable.containsKey("end_date")) {
            Object raw = mutable.get("end_date");

            try {
                if (raw instanceof String s && !s.isBlank()) {
                    LocalDate parsed = LocalDate.parse(s);

                    if (Objects.equals(before.endDate(), parsed)) {
                        mutable.remove("end_date");
                    } else {
                        mutable.put("end_date", parsed);
                    }

                } else {
                    if (before.endDate() == null) {
                        mutable.remove("end_date");
                    } else {
                        mutable.put("end_date", null);
                    }
                }
            } catch (DateTimeParseException e) {
                throw InvalidRequest.onField("endDate", "tenancy.date_invalid", raw); // Same as startDate
            }
        }

        if(mutable.isEmpty()) {
            return Optional.of(before.toTenancy());
        }

        // A key that survived the blocks above carries a real change; one that
        // did not means the supplied value already matched, so `before` is the
        // effective value either way.
        LocalDate startAfter = mutable.containsKey("start_date")
                ? (LocalDate) mutable.get("start_date")
                : before.startDate();
        LocalDate endAfter = mutable.containsKey("end_date")
                ? (LocalDate) mutable.get("end_date")
                : before.endDate();

        if (startAfter != null && endAfter != null && endAfter.isBefore(startAfter)) {
            throw InvalidRequest.onField("endDate", "tenancy.end_before_start", endAfter, startAfter);
        }

        // Moving either date (reopening included) must not put a third tenancy in any month.
        boolean datesChanged = mutable.containsKey("start_date") || mutable.containsKey("end_date");
        if (datesChanged) {
            requireRoomOnLot(before.lotId(), uuid, startAfter, endAfter);
        }

        Optional<TenancyRow> updatedTenancy = tenancyRepository.patch(uuid, mutable);
        if (updatedTenancy.isEmpty()) {
            return Optional.empty();
        }
        TenancyRow after = updatedTenancy.get();

        var diff = AuditMapper.diff(before, after);
        if(!diff.before().isEmpty()) {
            auditService.recordUpdate("tenancy", uuid, diff.before(), diff.after());
        }

        return Optional.of(after.toTenancy());
    }

    // ---- the two-per-month rule ------------------------------------------------

    /**
     * Throws a 409 if this tenancy would make any month have three tenancies on the lot.
     *
     * <p>Two may share a month: the household moving out and the one moving in.
     * A third may not. Dates are compared by month, so a tenancy that ends on
     * the 15th still counts for that whole month.
     *
     * <p>A missing start date counts as "since forever". A missing end date
     * counts as "still going".
     *
     * @param self the tenancy being changed, so it is not counted twice. Null when creating.
     */
    private void requireRoomOnLot(UUID lotId, UUID self, LocalDate start, LocalDate end) {
        MonthSpan target = MonthSpan.of(start, end);

        List<MonthSpan> others = tenancyRepository.findByLot(lotId).stream()
                .filter(row -> !Objects.equals(row.uuid(), self))
                .map(row -> MonthSpan.of(row.startDate(), row.endDate()))
                .filter(target::overlaps)
                .toList();

        // Any two others that share a month with each other AND with this one make three.
        for (int a = 0; a < others.size(); a++) {
            for (int b = a + 1; b < others.size(); b++) {
                MonthSpan shared = target.intersect(others.get(a));
                if (shared.overlaps(others.get(b))) {
                    YearMonth month = shared.intersect(others.get(b)).from();
                    throw RuleConflict.of("tenancy.lot_full_in_month", month);
                }
            }
        }
    }

    /** The months a tenancy covers, first and last included. */
    private record MonthSpan(YearMonth from, YearMonth to) {

        private static final YearMonth EARLIEST = YearMonth.of(1, 1);
        private static final YearMonth LATEST = YearMonth.of(9999, 12);

        static MonthSpan of(LocalDate start, LocalDate end) {
            return new MonthSpan(
                    start == null ? EARLIEST : YearMonth.from(start),
                    end == null ? LATEST : YearMonth.from(end));
        }

        boolean overlaps(MonthSpan other) {
            return !from.isAfter(other.to) && !other.from.isAfter(to);
        }

        /** Only call on two spans that overlap. */
        MonthSpan intersect(MonthSpan other) {
            YearMonth laterStart = from.isAfter(other.from) ? from : other.from;
            YearMonth earlierEnd = to.isBefore(other.to) ? to : other.to;
            return new MonthSpan(laterStart, earlierEnd);
        }
    }

    /**
     * Deleting is for a tenancy entered by mistake. A tenancy that happened is ended (an end date), not deleted.
     *
     * <p>A tenancy whose account has anything on the ledger cannot be deleted (409 tenancy.has_ledger): the
     * charges and payments would be left on an account nobody can reach. The account created with the tenancy is
     * left as the account is, so restoring the tenancy finds the same account again.
     *
     * <p>A tenancy with a generated document that was not abandoned cannot be deleted either (409
     * tenancy.has_paper): the paper carries a serial and may be in a tenant's hands. A draft does not stop the
     * delete; the draft stays with the deleted tenancy and comes back with a restore.
     */
    @Transactional
    public boolean softDelete(UUID uuid) {
        return tenancyRepository.findById(uuid).map(tenancy -> {
            if (tenancyRepository.hasLedgerEntries(uuid)) {
                throw RuleConflict.of("tenancy.has_ledger");
            }
            if (tenancyRepository.hasPaperOut(uuid)) {
                throw RuleConflict.of("tenancy.has_paper");
            }
            if (!tenancyRepository.softDelete(uuid)) {
                return false;
            }
            auditService.recordDelete("tenancy", uuid, AuditMapper.toMap(tenancy));
            return true;
        }).orElse(false);
    }

    /**
     * Brings a deleted tenancy back, for the undo of a delete. A tenancy that is not deleted is answered as the
     * tenancy is, so sending the restore twice is safe. Empty for an unknown tenancy.
     *
     * <p>Throws RuleConflict (409): tenancy.lot_deleted when the tenancy's lot was deleted meanwhile,
     * tenancy.lot_full when two other tenancies took the lot meanwhile, and
     * tenancy.lot_full_in_month when the tenancy would be a third in some month.
     */
    @Transactional
    public Optional<Tenancy> restore(UUID uuid) {
        Optional<TenancyRow> beforeOpt = tenancyRepository.findAnyById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        TenancyRow before = beforeOpt.get();
        if (before.deletedAt() == null) {
            return Optional.of(before.toTenancy());
        }

        Lot lot = lotService.findById(before.lotId())
                .orElseThrow(() -> RuleConflict.of("tenancy.lot_deleted"));
        boolean ended = before.endDate() != null && before.endDate().isBefore(LocalDate.now());
        if (!ended && tenancyRepository.countHolding(before.lotId(), uuid, before.startDate()) >= 2) {
            throw RuleConflict.of("tenancy.lot_full", lot.lotNumber());
        }
        requireRoomOnLot(before.lotId(), uuid, before.startDate(), before.endDate());

        Optional<TenancyRow> afterOpt = tenancyRepository.restore(uuid);
        if (afterOpt.isEmpty()) {
            return Optional.empty();
        }
        AuditMapper.Diff diff = AuditMapper.diff(before, afterOpt.get());
        auditService.recordUpdate("tenancy", uuid, diff.before(), diff.after());
        return Optional.of(afterOpt.get().toTenancy());
    }
}
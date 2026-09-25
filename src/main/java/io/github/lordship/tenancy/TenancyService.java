package io.github.lordship.tenancy;


import io.github.lordship.accounts.AccountService;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.lots.Lot;
import io.github.lordship.lots.LotService;
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
            throw new IllegalStateException("Lot " + lot.lotNumber()
                    + " cannot take a new tenancy: " + lot.notRentableReason());
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
                throw new IllegalArgumentException("Invalid date"); // Throws error if updated date is not valid
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
                throw new IllegalArgumentException("Invalid date"); // Same as startDate
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
            throw new IllegalArgumentException(
                    "endDate " + endAfter + " cannot be before startDate " + startAfter);
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
                    throw new IllegalStateException(
                            "Lot already has two tenancies in " + month + ". A lot can have at most two in any month");
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

    @Transactional
    public boolean softDelete(UUID uuid) {
        return tenancyRepository.findById(uuid).map(tenancy -> {
            if (!tenancyRepository.softDelete(uuid)) {
                return false;
            }
            auditService.recordDelete("tenancy", uuid, AuditMapper.toMap(tenancy));
            return true;
        }).orElse(false);
    }
}
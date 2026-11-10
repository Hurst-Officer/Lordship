package io.github.lordship.meterbills;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.meterbills.internal.UtilityCycleRepository;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.Patches;
import io.github.lordship.shared.RuleConflict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Each park's utility cycle, set by the GMs: how often a service is passed on,
 * in which months, and how long after the service period. A cycle holds from
 * its effective month until a later cycle for the same service starts.
 */
@Service
public class UtilityCycleService {

    private final UtilityCycleRepository cycles;
    private final AuditService auditService;

    public UtilityCycleService(UtilityCycleRepository cycles, AuditService auditService) {
        this.cycles = cycles;
        this.auditService = auditService;
    }

    public List<UtilityCycle> of(UUID propertyId) {
        return cycles.of(propertyId, false);
    }

    public List<UtilityCycle> deleted(UUID propertyId) {
        return cycles.of(propertyId, true);
    }

    public Optional<UUID> propertyOf(UUID uuid) {
        return cycles.findAny(uuid).map(UtilityCycle::propertyId);
    }

    /**
     * The rent month a bill for this service lands in, by the park's cycle: the
     * month its service period ends, plus the lag, moved on to the next month
     * that carries a charge. Empty when the park has no cycle for the service.
     */
    public Optional<LocalDate> proposedBillingMonth(UUID propertyId, UtilityService service, LocalDate periodEnd) {
        return cycles.inForce(propertyId, service, periodEnd).map(c -> billingMonth(c, periodEnd));
    }

    /** The rule behind {@link #proposedBillingMonth}, on its own so it can be checked without a database */
    public static LocalDate billingMonth(UtilityCycle cycle, LocalDate periodEnd) {
        YearMonth month = YearMonth.from(periodEnd).plusMonths(cycle.lagMonths());
        while (!carriesCharge(cycle, month.getMonthValue())) {
            month = month.plusMonths(1);
        }
        return month.atDay(1);
    }

    /** Whether a calendar month (1-12) is one this cycle charges in */
    public static boolean carriesCharge(UtilityCycle cycle, int month) {
        return Math.floorMod(month - cycle.anchorMonth(), cycle.cycleMonths()) == 0;
    }

    @Transactional
    public UtilityCycle create(UUID propertyId, UtilityService service, Integer cycleMonths, Integer anchorMonth,
                               Integer lagMonths, Integer readDay, LocalDate effectiveFrom, String notes) {
        if (service == null) throw InvalidRequest.onField("service", "value.required");
        if (effectiveFrom == null) throw InvalidRequest.onField("effectiveFrom", "value.required");
        LocalDate from = effectiveFrom.withDayOfMonth(1);
        requireRanges(cycleMonths, anchorMonth, lagMonths, readDay);
        if (cycles.startTaken(propertyId, service, from, null)) throw RuleConflict.of("utility_cycle.start_taken", from);

        UtilityCycle saved = cycles.insert(propertyId, service, cycleMonths, anchorMonth, lagMonths, readDay, from,
                Patches.text(notes));
        auditService.recordInsert(UtilityCycleRepository.TABLE, saved.uuid(), AuditMapper.toMap(saved));
        return saved;
    }

    @Transactional
    public Optional<UtilityCycle> patch(UUID uuid, Map<String, Object> raw) {
        Optional<UtilityCycle> found = cycles.find(uuid);
        if (found.isEmpty()) return Optional.empty();
        UtilityCycle before = found.get();

        Map<String, Object> changes = new HashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String col = e.getKey();
            Object v = e.getValue();
            switch (col) {
                case "cycle_months" -> changes.put(col, wholeOrNull(v, "cycleMonths"));
                case "anchor_month" -> changes.put(col, wholeOrNull(v, "anchorMonth"));
                case "lag_months" -> changes.put(col, wholeOrNull(v, "lagMonths"));
                case "read_day" -> changes.put(col, wholeOrNull(v, "readDay"));
                case "effective_from" -> {
                    LocalDate from = Patches.date(v, "effectiveFrom");
                    if (from == null) throw InvalidRequest.onField("effectiveFrom", "value.required");
                    from = from.withDayOfMonth(1);
                    if (cycles.startTaken(before.propertyId(), before.service(), from, uuid)) {
                        throw RuleConflict.of("utility_cycle.start_taken", from);
                    }
                    changes.put(col, from);
                }
                default -> changes.put(col, Patches.text(v));
            }
        }
        requireRanges(
                (Integer) changes.getOrDefault("cycle_months", before.cycleMonths()),
                (Integer) changes.getOrDefault("anchor_month", before.anchorMonth()),
                (Integer) changes.getOrDefault("lag_months", before.lagMonths()),
                changes.containsKey("read_day") ? (Integer) changes.get("read_day") : before.readDay());

        UtilityCycle after = cycles.patch(uuid, changes).orElse(null);
        if (after == null) return Optional.empty();
        var diff = AuditMapper.diff(before, after);
        if (!diff.before().isEmpty()) auditService.recordUpdate(UtilityCycleRepository.TABLE, uuid, diff.before(), diff.after());
        return Optional.of(after);
    }

    @Transactional
    public boolean delete(UUID uuid) {
        Optional<UtilityCycle> found = cycles.find(uuid);
        if (found.isEmpty() || !cycles.softDelete(uuid)) return false;
        auditService.recordDelete(UtilityCycleRepository.TABLE, uuid, AuditMapper.toMap(found.get()));
        return true;
    }

    // Refused when another cycle has since been set to start the same month
    @Transactional
    public Optional<UtilityCycle> restore(UUID uuid) {
        UtilityCycle before = cycles.findAny(uuid).orElse(null);
        if (before == null) return Optional.empty();
        if (before.deletedAt() != null) {
            if (cycles.startTaken(before.propertyId(), before.service(), before.effectiveFrom(), uuid)) {
                throw RuleConflict.of("utility_cycle.start_taken", before.effectiveFrom());
            }
            cycles.restore(uuid);
            UtilityCycle after = cycles.find(uuid).orElseThrow();
            var diff = AuditMapper.diff(before, after);
            auditService.recordUpdate(UtilityCycleRepository.TABLE, uuid, diff.before(), diff.after());
        }
        return cycles.find(uuid);
    }

    private static Integer wholeOrNull(Object raw, String field) {
        Long n = Patches.whole(raw, field);
        if (n == null) return null;
        if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) throw InvalidRequest.onField(field, "value.not_whole", raw);
        return n.intValue();
    }

    private static void requireRanges(Integer cycleMonths, Integer anchorMonth, Integer lagMonths, Integer readDay) {
        // A cycle that does not divide the year would charge in uneven months (every 5: Jan, Jun, Nov, then Jan again)
        if (cycleMonths == null || !java.util.Set.of(1, 2, 3, 4, 6, 12).contains(cycleMonths)) {
            throw InvalidRequest.onField("cycleMonths", "utility_cycle.months_divide_the_year");
        }
        if (anchorMonth == null || anchorMonth < 1 || anchorMonth > 12) {
            throw InvalidRequest.onField("anchorMonth", "utility_cycle.out_of_range", 1, 12);
        }
        if (lagMonths == null || lagMonths < 0 || lagMonths > 12) {
            throw InvalidRequest.onField("lagMonths", "utility_cycle.out_of_range", 0, 12);
        }
        if (readDay != null && (readDay < 1 || readDay > 31)) {
            throw InvalidRequest.onField("readDay", "utility_cycle.out_of_range", 1, 31);
        }
    }
}

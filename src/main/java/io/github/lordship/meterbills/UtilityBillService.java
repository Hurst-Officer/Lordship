package io.github.lordship.meterbills;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.meterbills.internal.UtilityBillRepository;
import io.github.lordship.meters.MeterMain;
import io.github.lordship.meters.MeterRead;
import io.github.lordship.meters.MeterReadKind;
import io.github.lordship.meters.MeterReadService;
import io.github.lordship.meters.MeterService;
import io.github.lordship.meters.MeterType;
import io.github.lordship.meters.MeterUnit;
import io.github.lordship.meters.ReadChain;
import io.github.lordship.meters.ReadInterval;
import io.github.lordship.meters.RateGroup;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.Patches;
import io.github.lordship.shared.RuleConflict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Utility bills as received: the bill, the mains it is for (with the old and
 * new reads printed for each), and its printed lines.
 *
 * <p>Typed in by hand for now. The lines are optional detail: a bill can be one
 * line per service, or every line printed on it when that helps. Each line lands
 * in its own rent month, proposed from the park's cycle for its service.
 *
 * <p>A bill keeps the rate group it was entered under, and only names mains of
 * that group. A bill that looks like one already entered is refused until the
 * person confirms it (allowDuplicate), after Glade has shown them the other one.
 *
 * <p>Nothing here passes a bill on to tenants yet. That waits for tenancies:
 * the rate is the bill's passed-on lines over the mains' usage, each lot is
 * charged its submeter usage at that rate, and the lots together are never
 * charged more than the passed-on lines. A shared submeter's share falls on the
 * occupied lots it feeds.
 */
@Service
public class UtilityBillService {

    // Reads and printed usage further apart than this share of the printed usage are worth a look
    private static final BigDecimal USAGE_TOLERANCE = new BigDecimal("0.01");
    // A provider prints usage rounded to whole units (Puyallup: whole hundreds of cubic feet),
    // so half a printed unit of difference is rounding, not a disagreement
    private static final BigDecimal HALF_A_PRINTED_UNIT = new BigDecimal("0.5");
    // How far back the bill list looks when no month is asked for
    private static final int DEFAULT_LIST_MONTHS = 13;

    private final UtilityBillRepository bills;
    private final UtilityCycleService cycleService;
    private final MeterService meterService;
    private final MeterReadService readService;
    private final AuditService auditService;

    public UtilityBillService(UtilityBillRepository bills, UtilityCycleService cycleService, MeterService meterService,
                              MeterReadService readService, AuditService auditService) {
        this.bills = bills;
        this.cycleService = cycleService;
        this.meterService = meterService;
        this.readService = readService;
        this.auditService = auditService;
    }

    // ── Reading ──

    /**
     * A park's bills with a line landing in a rent month (any day of it), or, with
     * no month, the bills whose service period ended in the last 13 months.
     */
    public List<BillDetail> of(UUID propertyId, LocalDate billingMonth) {
        List<UtilityBill> found = billingMonth != null
                ? bills.billsForMonth(propertyId, billingMonth.withDayOfMonth(1))
                : bills.billsEnding(propertyId, LocalDate.now().minusMonths(DEFAULT_LIST_MONTHS), LocalDate.now().plusYears(1));
        Map<UUID, List<ReadInterval>> chains = new HashMap<>();
        return found.stream().map(b -> detail(b, chains)).toList();
    }

    public List<UtilityBill> deleted(UUID propertyId) {
        return bills.deletedBills(propertyId);
    }

    public Optional<BillDetail> find(UUID uuid) {
        return bills.findBill(uuid).map(b -> detail(b, new HashMap<>()));
    }

    /** A bill's deleted lines and meters, for its deleted panel */
    public Map<String, List<?>> deletedParts(UUID billId) {
        return Map.of("lines", bills.linesOf(billId, true), "meters", bills.deletedMetersOf(billId));
    }

    /**
     * Bills that may be the one being entered: same park, overlapping service
     * period, and the same account, provider or total; or, given a main, any bill
     * naming that main for an overlapping period. Glade shows them before saving.
     */
    public List<BillDetail> lookAlikes(UUID propertyId, UUID exceptBillId, LocalDate periodStart, LocalDate periodEnd,
                                       String provider, String accountNumber, BigDecimal printedTotal, UUID mainId) {
        requirePeriod(periodStart, periodEnd);
        Map<UUID, UtilityBill> found = new java.util.LinkedHashMap<>();
        bills.lookAlikes(propertyId, exceptBillId, periodStart, periodEnd, Patches.text(provider),
                Patches.text(accountNumber), printedTotal).forEach(b -> found.put(b.uuid(), b));
        if (mainId != null) {
            bills.billsNamingMain(mainId, exceptBillId, periodStart, periodEnd).stream()
                    .filter(b -> b.propertyId().equals(propertyId))
                    .forEach(b -> found.put(b.uuid(), b));
        }
        Map<UUID, List<ReadInterval>> chains = new HashMap<>();
        return found.values().stream().map(b -> detail(b, chains)).toList();
    }

    // The property behind each record, deleted or not, for PropertyScope
    public Optional<UUID> propertyOfBill(UUID uuid) { return bills.propertyOfBill(uuid); }
    public Optional<UUID> propertyOfMeter(UUID uuid) { return bills.findAnyMeter(uuid).map(UtilityBillMeter::propertyId); }
    public Optional<UUID> propertyOfLine(UUID uuid) { return bills.propertyOfLine(uuid); }

    // ── Bills ──

    /** 409 utility_bill.possible_duplicate when it looks like a bill already entered and allowDuplicate is not set */
    @Transactional
    public BillDetail createBill(UUID propertyId, UUID rateGroupId, String provider, String accountNumber,
                                 LocalDate statementDate, LocalDate periodStart, LocalDate periodEnd,
                                 BigDecimal printedTotal, String notes, boolean allowDuplicate) {
        requirePeriod(periodStart, periodEnd);
        Patches.fits(printedTotal, 12, 2, "printedTotal");
        if (rateGroupId != null) requireRateGroupIn(rateGroupId, propertyId);
        String cleanProvider = Patches.text(provider);
        String cleanAccount = Patches.text(accountNumber);
        if (!allowDuplicate) {
            refuseLookAlikes(new UtilityBill(null, propertyId, rateGroupId, cleanProvider, cleanAccount, null,
                    periodStart, periodEnd, printedTotal, null, null, null, null, null));
        }

        UtilityBill saved = bills.insertBill(propertyId, rateGroupId, cleanProvider, cleanAccount, statementDate,
                periodStart, periodEnd, printedTotal, Patches.text(notes));
        auditService.recordInsert(UtilityBillRepository.BILL, saved.uuid(), AuditMapper.toMap(saved));
        return detail(saved, new HashMap<>());
    }

    /** A changed service period is checked for look-alikes again, unless allowDuplicate */
    @Transactional
    public Optional<BillDetail> patchBill(UUID uuid, Map<String, Object> raw, boolean allowDuplicate) {
        Optional<UtilityBill> found = bills.findBillForUpdate(uuid);
        if (found.isEmpty()) return Optional.empty();
        UtilityBill before = found.get();

        Map<String, Object> changes = new HashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String col = e.getKey();
            Object v = e.getValue();
            switch (col) {
                case "rate_group_id" -> {
                    UUID group = Patches.id(v, "rateGroupId");
                    if (group != null) requireRateGroupIn(group, before.propertyId());
                    changes.put(col, group);
                }
                case "statement_date" -> changes.put(col, Patches.date(v, "statementDate"));
                case "period_start" -> changes.put(col, requiredDate(v, "periodStart"));
                case "period_end" -> changes.put(col, requiredDate(v, "periodEnd"));
                case "printed_total" -> changes.put(col, Patches.fits(Patches.decimal(v, "printedTotal"), 12, 2, "printedTotal"));
                default -> changes.put(col, Patches.text(v));
            }
        }
        LocalDate start = (LocalDate) changes.getOrDefault("period_start", before.periodStart());
        LocalDate end = (LocalDate) changes.getOrDefault("period_end", before.periodEnd());
        requirePeriod(start, end);
        boolean periodMoved = !start.equals(before.periodStart()) || !end.equals(before.periodEnd());
        if (periodMoved && !allowDuplicate) {
            UtilityBill as = new UtilityBill(uuid, before.propertyId(), before.rateGroupId(),
                    (String) changes.getOrDefault("provider", before.provider()),
                    (String) changes.getOrDefault("account_number", before.accountNumber()),
                    null, start, end, (BigDecimal) changes.getOrDefault("printed_total", before.printedTotal()),
                    null, null, null, null, null);
            refuseLookAlikes(as);
            // addMeter checked each main against the old dates: the new dates get the same check
            for (UtilityBillMeter m : bills.metersOf(uuid)) {
                if (!bills.billsNamingMain(m.mainId(), uuid, start, end).isEmpty()) {
                    String title = meterService.findAnyMain(m.mainId()).map(MeterMain::title).orElse("");
                    throw RuleConflict.of("utility_bill.main_already_billed", title);
                }
            }
        }
        if (changes.containsKey("rate_group_id") && !Objects.equals(changes.get("rate_group_id"), before.rateGroupId())) {
            // The mains on the bill were checked against the old group; they must be in the new one now
            for (UtilityBillMeter m : bills.metersOf(uuid)) {
                MeterMain main = meterService.lockMain(m.mainId()).orElseThrow(
                        () -> RuleConflict.of("utility_bill.main_deleted", ""));
                if (!Objects.equals(main.rateGroupId(), changes.get("rate_group_id"))) {
                    throw RuleConflict.of("utility_bill.main_in_other_group", main.title());
                }
            }
        }

        UtilityBill after = bills.patchBill(uuid, changes).orElse(null);
        if (after == null) return Optional.empty();
        recordUpdate(UtilityBillRepository.BILL, uuid, before, after);
        return Optional.of(detail(after, new HashMap<>()));
    }

    @Transactional
    public boolean deleteBill(UUID uuid) {
        Optional<UtilityBill> found = bills.findBillForUpdate(uuid);
        if (found.isEmpty() || !bills.softDelete(UtilityBillRepository.BILL, uuid)) return false;
        auditService.recordDelete(UtilityBillRepository.BILL, uuid, AuditMapper.toMap(found.get()));
        return true;
    }

    /**
     * Refused when its rate group, one of its mains or a read it names has since
     * been deleted, or a main has moved to another group; and, unless
     * allowDuplicate, when it would now look like another live bill.
     */
    @Transactional
    public Optional<BillDetail> restoreBill(UUID uuid, boolean allowDuplicate) {
        UtilityBill before = bills.findAnyBill(uuid).orElse(null);
        if (before == null) return Optional.empty();
        if (before.deletedAt() != null) {
            if (before.rateGroupId() != null && meterService.holdRateGroup(before.rateGroupId()).isEmpty()) {
                throw RuleConflict.of("utility_bill.rate_group_deleted");
            }
            List<UtilityBillMeter> meters = bills.metersOf(uuid);
            for (UtilityBillMeter m : meters) requireMeterIntact(m, before, allowDuplicate);
            if (!allowDuplicate) refuseLookAlikes(before);
            if (bills.restore(UtilityBillRepository.BILL, uuid)) {
                recordUpdate(UtilityBillRepository.BILL, uuid, before, bills.findBill(uuid).orElseThrow());
            }
        }
        return find(uuid);
    }

    // ── The mains a bill is for ──

    /**
     * Names a main on a bill, with the old and new reads printed for it and/or
     * the usage printed. Printed reads become provider reads of the main: one
     * already on file for that day is reused when it agrees, and refused (409)
     * when it shows another number. Empty when the bill is not live (404).
     *
     * <p>The main always ends up with a usage the rate can use. Reads that cannot
     * be counted (the new one is lower, or the meter was restarted in between)
     * are refused unless the printed usage is given too; a dial that passed its
     * top during the bill is entered with {@code currentRollover}.
     */
    @Transactional
    public Optional<BillDetail> addMeter(UUID billId, UUID mainId,
                                         LocalDate priorReadOn, Long priorReading, boolean priorEstimated,
                                         LocalDate currentReadOn, Long currentReading, boolean currentEstimated,
                                         boolean currentRollover,
                                         BigDecimal printedUsage, MeterUnit printedUnit, boolean allowDuplicate) {
        Optional<UtilityBill> found = bills.findBillForUpdate(billId);
        if (found.isEmpty()) return Optional.empty();
        UtilityBill bill = found.get();
        // A main in another park is answered as if it did not exist, before anything is locked or read from it
        if (meterService.findMain(mainId).filter(m -> m.propertyId().equals(bill.propertyId())).isEmpty()) {
            throw InvalidRequest.onField("mainId", "meter_main.unknown");
        }
        // Locked (the bill first, then the main) so two bills naming it are checked one at a time
        MeterMain main = meterService.lockMain(mainId)
                .orElseThrow(() -> InvalidRequest.onField("mainId", "meter_main.unknown"));
        if (!Objects.equals(main.rateGroupId(), bill.rateGroupId())) {
            throw RuleConflict.of("utility_bill.main_in_other_group", main.title());
        }
        if (bills.mainIsOnBill(billId, mainId)) throw RuleConflict.of("utility_bill.main_twice", main.title());

        boolean hasPrior = priorReadOn != null || priorReading != null;
        boolean hasCurrent = currentReadOn != null || currentReading != null;
        if (hasPrior != hasCurrent) throw InvalidRequest.of("utility_bill.reads_in_pairs");
        if (hasPrior) {
            if (priorReadOn == null) throw InvalidRequest.onField("priorReadOn", "value.required");
            if (priorReading == null) throw InvalidRequest.onField("priorReading", "value.required");
            if (currentReadOn == null) throw InvalidRequest.onField("currentReadOn", "value.required");
            if (currentReading == null) throw InvalidRequest.onField("currentReading", "value.required");
            if (!currentReadOn.isAfter(priorReadOn)) throw InvalidRequest.onField("currentReadOn", "utility_bill.reads_backwards");
        }
        requirePrintedUsage(printedUsage, printedUnit, main.utilityType());
        if (!hasPrior && printedUsage == null) throw InvalidRequest.of("utility_bill.meter_needs_usage");
        if (!allowDuplicate && !bills.billsNamingMain(mainId, billId, bill.periodStart(), bill.periodEnd()).isEmpty()) {
            throw RuleConflict.of("utility_bill.main_already_billed", main.title());
        }

        UUID priorId = null;
        UUID currentId = null;
        if (hasPrior) {
            priorId = readService.providerRead(mainId, priorReadOn, priorReading, priorEstimated, null).uuid();
            currentId = readService.providerRead(mainId, currentReadOn, currentReading, currentEstimated,
                    currentRollover ? MeterReadKind.ROLLOVER : MeterReadKind.READ).uuid();
            if (printedUsage == null) requireCountable(main, priorId, currentId);
        }

        UtilityBillMeter saved = bills.insertMeter(billId, mainId, bill.propertyId(), priorId, currentId,
                printedUsage, printedUnit);
        auditService.recordInsert(UtilityBillRepository.METER, saved.uuid(), AuditMapper.toMap(saved));
        return Optional.of(detail(bill, new HashMap<>()));
    }

    /** Only the printed usage changes here; a printed read is corrected by taking the main off and adding it back */
    @Transactional
    public Optional<BillDetail> patchMeter(UUID uuid, Map<String, Object> raw) {
        UtilityBillMeter peek = bills.findAnyMeter(uuid).orElse(null);
        if (peek == null || bills.findBillForUpdate(peek.billId()).isEmpty()) return Optional.empty();
        Optional<UtilityBillMeter> found = bills.findMeter(uuid);
        if (found.isEmpty()) return Optional.empty();
        UtilityBillMeter before = found.get();
        MeterMain main = meterService.findAnyMain(before.mainId()).orElseThrow();

        Map<String, Object> changes = new HashMap<>();
        if (raw.containsKey("printed_usage")) changes.put("printed_usage", Patches.decimal(raw.get("printed_usage"), "printedUsage"));
        if (raw.containsKey("printed_unit")) changes.put("printed_unit", Patches.choice(raw.get("printed_unit"), MeterUnit.class, "printedUnit"));
        BigDecimal usage = changes.containsKey("printed_usage") ? (BigDecimal) changes.get("printed_usage") : before.printedUsage();
        MeterUnit unit = changes.containsKey("printed_unit") ? (MeterUnit) changes.get("printed_unit") : before.printedUnit();
        requirePrintedUsage(usage, unit, main.utilityType());
        if (usage == null && before.priorReadId() == null) throw InvalidRequest.of("utility_bill.meter_needs_usage");
        // Taking the printed usage away leaves the reads to carry the bill: they must be countable
        if (usage == null) requireCountable(main, before.priorReadId(), before.currentReadId());

        UtilityBillMeter after = bills.patchMeter(uuid, changes).orElse(null);
        if (after == null) return Optional.empty();
        recordUpdate(UtilityBillRepository.METER, uuid, before, after);
        return find(after.billId());
    }

    // The provider reads stay: they are still what the provider printed
    @Transactional
    public boolean deleteMeter(UUID uuid) {
        UtilityBillMeter peek = bills.findAnyMeter(uuid).orElse(null);
        if (peek == null || bills.findBillForUpdate(peek.billId()).isEmpty()) return false;
        Optional<UtilityBillMeter> found = bills.findMeter(uuid);
        if (found.isEmpty() || !bills.softDelete(UtilityBillRepository.METER, uuid)) return false;
        auditService.recordDelete(UtilityBillRepository.METER, uuid, AuditMapper.toMap(found.get()));
        return true;
    }

    /**
     * Refused when the main is on the bill again, the main or its reads have since
     * been deleted, the main has moved to another rate group, or (unless
     * allowDuplicate) another bill now names the main for these dates.
     */
    @Transactional
    public Optional<BillDetail> restoreMeter(UUID uuid, boolean allowDuplicate) {
        UtilityBillMeter before = bills.findAnyMeter(uuid).orElse(null);
        if (before == null) return Optional.empty();
        Optional<UtilityBill> bill = bills.findBillForUpdate(before.billId());
        if (bill.isEmpty()) return Optional.empty();
        if (before.deletedAt() != null) {
            if (bills.mainIsOnBill(before.billId(), before.mainId())) {
                String title = meterService.findAnyMain(before.mainId()).map(MeterMain::title).orElse("");
                throw RuleConflict.of("utility_bill.main_twice", title);
            }
            requireMeterIntact(before, bill.get(), allowDuplicate);
            bills.restore(UtilityBillRepository.METER, uuid);
            recordUpdate(UtilityBillRepository.METER, uuid, before, bills.findAnyMeter(uuid).orElseThrow());
        }
        return find(before.billId());
    }

    // ── Lines ──

    /**
     * A printed line. Its billing month, when not given, comes from the cycle for
     * its service (the bill's rate group's own, else the park's) and the bill's
     * period; with no cycle it must be given.
     * Empty when the bill is not live (404).
     */
    @Transactional
    public Optional<BillDetail> addLine(UUID billId, UtilityService service, String description, BigDecimal amount,
                                        BigDecimal printedRate, MeterUnit rateUnit, Boolean passedOn,
                                        LocalDate billingMonth, Integer sortOrder) {
        Optional<UtilityBill> found = bills.findBillForUpdate(billId);
        if (found.isEmpty()) return Optional.empty();
        UtilityBill bill = found.get();
        if (service == null) throw InvalidRequest.onField("service", "value.required");
        String text = Patches.requiredText(description, "description");
        if (amount == null) throw InvalidRequest.onField("amount", "value.required");
        Patches.fits(amount, 12, 2, "amount");
        Patches.fits(printedRate, 18, 8, "printedRate");
        if (printedRate != null && rateUnit == null) throw InvalidRequest.onField("rateUnit", "utility_bill.rate_needs_unit");
        LocalDate month = billingMonth != null ? billingMonth.withDayOfMonth(1)
                : cycleService.proposedBillingMonth(bill.propertyId(), bill.rateGroupId(), service, bill.periodEnd())
                .orElseThrow(() -> InvalidRequest.onField("billingMonth", "utility_bill.no_cycle"));

        UtilityBillLine saved = bills.insertLine(billId, service, text, amount, printedRate, rateUnit,
                passedOn == null || passedOn, month, sortOrder);
        auditService.recordInsert(UtilityBillRepository.LINE, saved.uuid(), AuditMapper.toMap(saved));
        return Optional.of(detail(bill, new HashMap<>()));
    }

    @Transactional
    public Optional<BillDetail> patchLine(UUID uuid, Map<String, Object> raw) {
        UtilityBillLine peek = bills.findAnyLine(uuid).orElse(null);
        if (peek == null || bills.findBillForUpdate(peek.billId()).isEmpty()) return Optional.empty();
        Optional<UtilityBillLine> found = bills.findLine(uuid);
        if (found.isEmpty()) return Optional.empty();
        UtilityBillLine before = found.get();

        Map<String, Object> changes = new HashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String col = e.getKey();
            Object v = e.getValue();
            switch (col) {
                case "service" -> {
                    UtilityService service = Patches.choice(v, UtilityService.class, "service");
                    if (service == null) throw InvalidRequest.onField("service", "value.required");
                    changes.put(col, service);
                }
                case "description" -> changes.put(col, Patches.requiredText(v, "description"));
                case "amount" -> {
                    BigDecimal amount = Patches.decimal(v, "amount");
                    if (amount == null) throw InvalidRequest.onField("amount", "value.required");
                    changes.put(col, Patches.fits(amount, 12, 2, "amount"));
                }
                case "printed_rate" -> changes.put(col, Patches.fits(Patches.decimal(v, "printedRate"), 18, 8, "printedRate"));
                case "rate_unit" -> changes.put(col, Patches.choice(v, MeterUnit.class, "rateUnit"));
                case "passed_on" -> changes.put(col, !Boolean.FALSE.equals(Patches.bool(v, "passedOn")));
                case "billing_month" -> changes.put(col, requiredDate(v, "billingMonth").withDayOfMonth(1));
                case "sort_order" -> {
                    Long order = Patches.whole(v, "sortOrder");
                    if (order == null) throw InvalidRequest.onField("sortOrder", "value.required");
                    changes.put(col, order.intValue());
                }
                default -> throw new IllegalArgumentException("Invalid column: " + col);
            }
        }
        Object rate = changes.containsKey("printed_rate") ? changes.get("printed_rate") : before.printedRate();
        Object unit = changes.containsKey("rate_unit") ? changes.get("rate_unit") : before.rateUnit();
        if (rate != null && unit == null) throw InvalidRequest.onField("rateUnit", "utility_bill.rate_needs_unit");

        UtilityBillLine after = bills.patchLine(uuid, changes).orElse(null);
        if (after == null) return Optional.empty();
        recordUpdate(UtilityBillRepository.LINE, uuid, before, after);
        return find(after.billId());
    }

    @Transactional
    public boolean deleteLine(UUID uuid) {
        UtilityBillLine peek = bills.findAnyLine(uuid).orElse(null);
        if (peek == null || bills.findBillForUpdate(peek.billId()).isEmpty()) return false;
        Optional<UtilityBillLine> found = bills.findLine(uuid);
        if (found.isEmpty() || !bills.softDelete(UtilityBillRepository.LINE, uuid)) return false;
        auditService.recordDelete(UtilityBillRepository.LINE, uuid, AuditMapper.toMap(found.get()));
        return true;
    }

    @Transactional
    public Optional<BillDetail> restoreLine(UUID uuid) {
        UtilityBillLine before = bills.findAnyLine(uuid).orElse(null);
        if (before == null || bills.findBillForUpdate(before.billId()).isEmpty()) return Optional.empty();
        if (bills.restore(UtilityBillRepository.LINE, uuid)) {
            recordUpdate(UtilityBillRepository.LINE, uuid, before, bills.findAnyLine(uuid).orElseThrow());
        }
        return find(before.billId());
    }

    // ── The checks ──

    // chains: each main's read history, loaded once per request however many bills name it
    private BillDetail detail(UtilityBill bill, Map<UUID, List<ReadInterval>> chains) {
        List<UtilityBillLine> lines = bills.linesOf(bill.uuid(), false);
        BigDecimal linesTotal = lines.stream().map(UtilityBillLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal passedOn = lines.stream().filter(l -> Boolean.TRUE.equals(l.passedOn()))
                .map(UtilityBillLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        Boolean totalMatches = bill.printedTotal() == null ? null : linesTotal.compareTo(bill.printedTotal()) == 0;

        List<BillDetail.Meter> meters = bills.metersOf(bill.uuid()).stream().map(m -> meterDetail(m, chains)).toList();
        return new BillDetail(bill, meters, lines, linesTotal, passedOn, totalMatches);
    }

    private BillDetail.Meter meterDetail(UtilityBillMeter m, Map<UUID, List<ReadInterval>> chains) {
        String title = meterService.findAnyMain(m.mainId()).map(MeterMain::title).orElse(null);
        MeterRead prior = m.priorReadId() == null ? null : readService.findAny(m.priorReadId()).orElse(null);
        MeterRead current = m.currentReadId() == null ? null : readService.findAny(m.currentReadId()).orElse(null);

        BigDecimal readsUsage = null;
        if (prior != null && current != null) {
            List<ReadInterval> chain = chains.computeIfAbsent(m.mainId(), id -> readService.history(id, null));
            readsUsage = ReadChain.baseUsageBetween(chain, prior.uuid(), current.uuid());
        }
        BigDecimal printedUsage = (m.printedUsage() == null || m.printedUnit() == null) ? null
                : m.printedUnit().toBase(m.printedUsage());
        BigDecimal used = printedUsage != null ? printedUsage : readsUsage;
        return new BillDetail.Meter(m, title, prior, current, readsUsage, printedUsage, used,
                usageMatches(readsUsage, printedUsage, m.printedUnit()));
    }

    /**
     * Whether the usage worked out from the reads agrees with the usage printed on the bill,
     * both in base units (gallons or kWh). The allowed gap is the larger of 1% of the printed
     * usage and half of one printed unit: at 44 CCF, rounding alone can be off by 1.1%.
     */
    static Boolean usageMatches(BigDecimal fromReads, BigDecimal printed, MeterUnit printedUnit) {
        if (fromReads == null || printed == null || printedUnit == null) return null;
        BigDecimal allowed = printed.abs().multiply(USAGE_TOLERANCE).max(printedUnit.toBase(HALF_A_PRINTED_UNIT));
        return fromReads.subtract(printed).abs().compareTo(allowed) <= 0;
    }

    // ── Rules ──

    // A bill meter comes back only as addMeter would have let it in: main live and in the bill's group, reads live and countable
    private void requireMeterIntact(UtilityBillMeter m, UtilityBill bill, boolean allowDuplicate) {
        MeterMain main = meterService.lockMain(m.mainId()).orElse(null);
        if (main == null) {
            String title = meterService.findAnyMain(m.mainId()).map(MeterMain::title).orElse("");
            throw RuleConflict.of("utility_bill.main_deleted", title);
        }
        if (!Objects.equals(main.rateGroupId(), bill.rateGroupId())) {
            throw RuleConflict.of("utility_bill.main_in_other_group", main.title());
        }
        if (!bills.readsLive(m)) throw RuleConflict.of("utility_bill.reads_deleted", main.title());
        // Reads entered or corrected while it was deleted may have left it with nothing to count
        if (m.printedUsage() == null) requireCountable(main, m.priorReadId(), m.currentReadId());
        if (!allowDuplicate && !bills.billsNamingMain(m.mainId(), bill.uuid(), bill.periodStart(), bill.periodEnd()).isEmpty()) {
            throw RuleConflict.of("utility_bill.main_already_billed", main.title());
        }
    }

    // Without printed usage, a bill's usage for a main is what its reads add up to: 409 when they add up to nothing
    private void requireCountable(MeterMain main, UUID priorReadId, UUID currentReadId) {
        List<ReadInterval> chain = readService.history(main.uuid(), null);
        if (ReadChain.baseUsageBetween(chain, priorReadId, currentReadId) == null) {
            throw RuleConflict.of("utility_bill.reads_not_countable", main.title());
        }
    }

    // 409 utility_bill.possible_duplicate when another live bill looks like this one
    private void refuseLookAlikes(UtilityBill bill) {
        List<UtilityBill> alike = bills.lookAlikes(bill.propertyId(), bill.uuid(), bill.periodStart(), bill.periodEnd(),
                bill.provider(), bill.accountNumber(), bill.printedTotal());
        if (!alike.isEmpty()) {
            throw RuleConflict.of("utility_bill.possible_duplicate", alike.getFirst().periodStart(), alike.getFirst().periodEnd());
        }
    }

    private void requireRateGroupIn(UUID rateGroupId, UUID propertyId) {
        RateGroup group = meterService.holdRateGroup(rateGroupId)
                .orElseThrow(() -> InvalidRequest.onField("rateGroupId", "rate_group.unknown"));
        if (!group.propertyId().equals(propertyId)) throw InvalidRequest.onField("rateGroupId", "rate_group.unknown");
    }

    private static void requirePeriod(LocalDate start, LocalDate end) {
        if (start == null) throw InvalidRequest.onField("periodStart", "value.required");
        if (end == null) throw InvalidRequest.onField("periodEnd", "value.required");
        if (!end.isAfter(start)) throw InvalidRequest.onField("periodEnd", "utility_bill.period_backwards");
    }

    private static LocalDate requiredDate(Object raw, String field) {
        LocalDate d = Patches.date(raw, field);
        if (d == null) throw InvalidRequest.onField(field, "value.required");
        return d;
    }

    private static void requirePrintedUsage(BigDecimal usage, MeterUnit unit, MeterType type) {
        if ((usage == null) != (unit == null)) throw InvalidRequest.onField("printedUnit", "utility_bill.usage_needs_unit");
        if (usage != null && usage.signum() < 0) throw InvalidRequest.onField("printedUsage", "meter_read.negative");
        Patches.fits(usage, 18, 4, "printedUsage");
        if (unit != null && !unit.suits(type)) throw InvalidRequest.onField("printedUnit", "meter.unit_does_not_suit", unit, type);
    }

    private void recordUpdate(String table, UUID uuid, Record before, Record after) {
        var diff = AuditMapper.diff(before, after);
        diff.before().remove("updatedAt");
        diff.after().remove("updatedAt");
        if (!diff.before().isEmpty()) auditService.recordUpdate(table, uuid, diff.before(), diff.after());
    }
}

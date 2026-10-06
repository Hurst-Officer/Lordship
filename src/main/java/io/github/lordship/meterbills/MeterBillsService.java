package io.github.lordship.meterbills;

import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.meterbills.internal.MeterBillsCreateRequest;
import io.github.lordship.meterbills.internal.MeterBillsRepository;
import io.github.lordship.meterbills.internal.MeterBillsRow;
import io.github.lordship.meters.MeterService;
import io.github.lordship.meters.Meters;
import io.github.lordship.meters.Usage;
import io.github.lordship.shared.EncryptionService;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class MeterBillsService {

    private final MeterBillsRepository meterBillsRepository;
    private final MeterService meterService;
    private final EncryptionService encryptionService;
    private final AuditService auditService;

    private static final Logger log = LoggerFactory.getLogger(MeterBillsService.class);

    public MeterBillsService(
            MeterBillsRepository meterBillsRepository,
            MeterService meterService,
            EncryptionService encryptionService,
            AuditService auditService

    ) {
        this.meterBillsRepository = meterBillsRepository;
        this.meterService = meterService;
        this.encryptionService = encryptionService;
        this.auditService = auditService;
    }

    @Transactional
    public MeterBills create(MeterBillsCreateRequest request) {
        MeterBillsRow row = meterBillsRepository.save(
                MeterBillsRow.forInsert(
                        request.billedMeterId(),
                        request.billedAmount(),
                        request.rateAmount(),
                        request.rateUnit(),
                        request.periodStart(),
                        request.periodEnd()
                )
        );

        auditService.recordInsert("meter_billing", row.uuid(), AuditMapper.toMap(row));
        return row.toMeterBills();
    }

    public Optional<MeterBills> findMeterBillById(UUID uuid) {
        return meterBillsRepository.findById(uuid).map(MeterBillsRow::toMeterBills);
    }

    public List<MeterBills> findMeterByBilling(UUID meterId) {
        return meterBillsRepository.findByBilledMeter(meterId)
                .stream()
                .map(MeterBillsRow::toMeterBills)
                .toList();
    }

    @Transactional
    public Optional<MeterBills> patchMeterBill(UUID uuid, Map<String, Object> changes) {
        Optional<MeterBillsRow> beforeOpt = meterBillsRepository.findById(uuid);
        if (beforeOpt.isEmpty()) {
            return Optional.empty();
        }
        MeterBillsRow before = beforeOpt.get();

        Map<String, Object> mutable = new HashMap<>(changes);

        if (mutable.containsKey("billed_amount")) {
            Object raw = mutable.get("billed_amount");
            Double newAmount;

            if (raw == null) {
                newAmount = null;
            } else if (raw instanceof Number n) {
                newAmount = n.doubleValue();
            } else {
                // assume string
                String s = raw.toString().trim();
                if (s.isEmpty()) {
                    newAmount = null;
                } else {
                    try {
                        newAmount = Double.parseDouble(s);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Must be a decimal value");
                    }
                }
            }

            if (Objects.equals(before.billedAmount(), newAmount)) {
                mutable.remove("billed_amount");
            } else {
                mutable.put("billed_amount", newAmount);
            }
        }

        if (mutable.containsKey("rate_amount")) {
            Object raw = mutable.get("rate_amount");
            Double newAmount;

            if (raw == null) {
                newAmount = null;
            } else if (raw instanceof Number n) {
                newAmount = n.doubleValue();
            } else {
                // assume string
                String s = raw.toString().trim();
                if (s.isEmpty()) {
                    newAmount = null;
                } else {
                    try {
                        newAmount = Double.parseDouble(s);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Must be a decimal value");
                    }
                }
            }

            if (Objects.equals(before.rateAmount(), newAmount)) {
                mutable.remove("rate_amount");
            } else {
                mutable.put("rate_amount", newAmount);
            }
        }

        if (mutable.containsKey("period_start")) {
            Object raw = mutable.get("period_start");
            try {
                if (raw instanceof String s && !s.isBlank()) {
                    LocalDate parsed = LocalDate.parse(s);

                    if (Objects.equals(before.periodStart(), parsed)) {
                        mutable.remove("period_start");
                    } else {
                        mutable.put("period_start", parsed);
                    }

                } else {
                    if (before.periodStart() == null) {
                        mutable.remove("period_start");
                    } else {
                        mutable.put("period_start", null);
                    }
                }
            } catch(DateTimeParseException e){
                throw new IllegalArgumentException("Invalid date"); // Throws error if updated date is not valid
            }
        }

        if (mutable.containsKey("period_end")) {
            Object raw = mutable.get("period_end");
            try {
                if (raw instanceof String s && !s.isBlank()) {
                    LocalDate parsed = LocalDate.parse(s);

                    if (Objects.equals(before.periodEnd(), parsed)) {
                        mutable.remove("period_end");
                    } else {
                        mutable.put("period_end", parsed);
                    }

                } else {
                    if (before.periodEnd() == null) {
                        mutable.remove("period_end");
                    } else {
                        mutable.put("period_end", null);
                    }
                }
            } catch(DateTimeParseException e){
                throw new IllegalArgumentException("Invalid date"); // Throws error if updated date is not valid
            }
        }

        if(mutable.isEmpty()) {
            return Optional.of(before.toMeterBills());
        }

        Optional<MeterBillsRow> updatedMeterBill = meterBillsRepository.patch(uuid, mutable);
        if (updatedMeterBill.isEmpty()) {
            return Optional.empty();
        }
        MeterBillsRow after = updatedMeterBill.get();

        var diff = AuditMapper.diff(before, after);
        if(!diff.before().isEmpty()) {
            auditService.recordUpdate("meter_billing", uuid, diff.before(), diff.after());
        }

        return Optional.of(after.toMeterBills());
    }

    @Transactional
    public boolean softDelete(UUID uuid) {
        return meterBillsRepository.findById(uuid).map(meterRow -> {
            meterBillsRepository.softDelete(uuid);
            auditService.recordDelete("meter_billing", uuid, AuditMapper.toMap(meterRow));
            return true;
        }).orElse(false);
    }

    private BigDecimal parseDecimal(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        String s = raw.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Must be a decimal number");
        }
    }

    public ChargeCalculation calculateCharge(UUID lotMeterId, LocalDate periodStart, LocalDate periodEnd) {
        UUID parentMeterId = meterService.resolveParentMeter(lotMeterId, periodEnd)
                .orElseThrow(() -> new IllegalStateException(
                        "No active parent meter for lot meter " + lotMeterId + " as of " + periodEnd));

        MeterBillsRow rateRow = meterBillsRepository.findRateForPeriod(parentMeterId, periodStart, periodEnd)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No billed rate on file for parent meter " + parentMeterId +
                                " covering [" + periodStart + ", " + periodEnd + "]"));

        Meters lotMeter = meterService.findMetersById(lotMeterId)
                .orElseThrow(() -> new EntityNotFoundException("Meter not found: " + lotMeterId));

        if (lotMeter.measurement() != rateRow.rateUnit()) {
            throw new IllegalStateException(
                    "Rate unit mismatch: parent meter billed in " + rateRow.rateUnit() +
                            " but lot meter " + lotMeterId + " measures in " + lotMeter.measurement());
        }

        OffsetDateTime start = periodStart.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime end = periodEnd.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();

        Usage usage = meterService.getUsageForPeriod(lotMeterId, start, end);

        BigDecimal usageAmount = BigDecimal.valueOf(usage.usage());
        BigDecimal calculatedAmount = rateRow.rateAmount().multiply(usageAmount);

        return new ChargeCalculation(
                lotMeterId, parentMeterId, periodStart, periodEnd,
                usageAmount, rateRow.rateAmount(), calculatedAmount,
                usage.startRead().isEstimated(), usage.endRead().isEstimated()
        );
    }
}


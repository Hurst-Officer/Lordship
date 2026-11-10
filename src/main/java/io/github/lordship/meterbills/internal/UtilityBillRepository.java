package io.github.lordship.meterbills.internal;

import io.github.lordship.meterbills.UtilityBill;
import io.github.lordship.meterbills.UtilityBillLine;
import io.github.lordship.meterbills.UtilityBillMeter;
import io.github.lordship.meterbills.UtilityService;
import io.github.lordship.meters.MeterUnit;
import io.github.lordship.shared.Patches;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Bills, the mains each bill is for, and the bill's printed lines
@Repository
public class UtilityBillRepository {

    public static final String BILL = "utility_bill";
    public static final String METER = "utility_bill_meter";
    public static final String LINE = "utility_bill_line";

    // document_file is set by the upload, not by a PATCH
    public static final Set<String> BILL_COLUMNS = Set.of(
            "rate_group_id", "provider", "account_number", "statement_date", "period_start", "period_end",
            "printed_total", "notes");
    public static final Set<String> METER_COLUMNS = Set.of("printed_usage", "printed_unit");
    public static final Set<String> LINE_COLUMNS = Set.of(
            "service", "description", "amount", "printed_rate", "rate_unit", "passed_on", "billing_month", "sort_order");

    private static final Map<String, String> CASTS = Map.of(
            "printed_unit", "meter_unit", "rate_unit", "meter_unit", "service", "utility_service");

    private final JdbcClient jdbc;

    public UtilityBillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── Bills ──

    public UtilityBill insertBill(UUID propertyId, UUID rateGroupId, String provider, String accountNumber,
                                  LocalDate statementDate, LocalDate periodStart, LocalDate periodEnd,
                                  BigDecimal printedTotal, String notes) {
        return jdbc.sql("""
                        INSERT INTO utility_bill (property_id, rate_group_id, provider, account_number, statement_date,
                                                  period_start, period_end, printed_total, notes)
                        VALUES (:propertyId, :rateGroupId, :provider, :accountNumber, :statementDate,
                                :periodStart, :periodEnd, :printedTotal, :notes)
                        RETURNING *
                        """)
                .param("propertyId", propertyId).param("rateGroupId", rateGroupId).param("provider", provider)
                .param("accountNumber", accountNumber).param("statementDate", statementDate)
                .param("periodStart", periodStart).param("periodEnd", periodEnd)
                .param("printedTotal", printedTotal).param("notes", notes)
                .query(UtilityBill.class).single();
    }

    public Optional<UtilityBill> findBill(UUID uuid) {
        return jdbc.sql("SELECT * FROM utility_bill WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid).query(UtilityBill.class).optional();
    }

    // Locked until the transaction ends: a bill and its parts change one save at a time
    public Optional<UtilityBill> findBillForUpdate(UUID uuid) {
        return jdbc.sql("SELECT * FROM utility_bill WHERE uuid = :uuid AND deleted_at IS NULL FOR UPDATE")
                .param("uuid", uuid).query(UtilityBill.class).optional();
    }

    public Optional<UtilityBill> findAnyBill(UUID uuid) {
        return jdbc.sql("SELECT * FROM utility_bill WHERE uuid = :uuid")
                .param("uuid", uuid).query(UtilityBill.class).optional();
    }

    // A park's live bills with a line landing in a rent month, and those with no lines yet
    // (so a bill just entered is not lost from view), newest service period first
    public List<UtilityBill> billsForMonth(UUID propertyId, LocalDate billingMonth) {
        return jdbc.sql("""
                        SELECT b.* FROM utility_bill b
                         WHERE b.property_id = :propertyId AND b.deleted_at IS NULL
                           AND (EXISTS (SELECT 1 FROM utility_bill_line l
                                         WHERE l.bill_id = b.uuid AND l.deleted_at IS NULL AND l.billing_month = :month)
                                OR NOT EXISTS (SELECT 1 FROM utility_bill_line l
                                                WHERE l.bill_id = b.uuid AND l.deleted_at IS NULL))
                         ORDER BY b.period_end DESC, b.provider
                        """)
                .param("propertyId", propertyId).param("month", billingMonth)
                .query(UtilityBill.class).list();
    }

    // A park's live bills whose service period ends in [from, to], newest first
    public List<UtilityBill> billsEnding(UUID propertyId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT * FROM utility_bill
                         WHERE property_id = :propertyId AND deleted_at IS NULL AND period_end BETWEEN :from AND :to
                         ORDER BY period_end DESC, provider
                        """)
                .param("propertyId", propertyId).param("from", from).param("to", to)
                .query(UtilityBill.class).list();
    }

    public List<UtilityBill> deletedBills(UUID propertyId) {
        return jdbc.sql("SELECT * FROM utility_bill WHERE property_id = :propertyId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC")
                .param("propertyId", propertyId).query(UtilityBill.class).list();
    }

    /**
     * Live bills that look like the same bill entered twice: in the park, other
     * than {@code except}, with an overlapping service period, and the same account
     * number, or (no account numbers) the same provider, or the same printed total.
     */
    public List<UtilityBill> lookAlikes(UUID propertyId, UUID except, LocalDate periodStart, LocalDate periodEnd,
                                        String provider, String accountNumber, BigDecimal printedTotal) {
        return jdbc.sql("""
                        SELECT * FROM utility_bill
                         WHERE property_id = :propertyId AND deleted_at IS NULL AND uuid IS DISTINCT FROM :except
                           AND period_start < :periodEnd AND :periodStart < period_end
                           AND ( (account_number IS NOT NULL AND CAST(:account AS text) IS NOT NULL
                                    AND lower(trim(account_number)) = lower(trim(:account)))
                              OR (account_number IS NULL AND CAST(:account AS text) IS NULL AND provider IS NOT NULL
                                    AND CAST(:provider AS text) IS NOT NULL AND lower(trim(provider)) = lower(trim(:provider)))
                              OR (printed_total IS NOT NULL AND printed_total = :total) )
                         ORDER BY period_end DESC
                        """)
                .param("propertyId", propertyId).param("except", except)
                .param("periodStart", periodStart).param("periodEnd", periodEnd)
                .param("account", accountNumber).param("provider", provider).param("total", printedTotal)
                .query(UtilityBill.class).list();
    }

    // Live bills, other than one, that name this main for an overlapping service period
    public List<UtilityBill> billsNamingMain(UUID mainId, UUID except, LocalDate periodStart, LocalDate periodEnd) {
        return jdbc.sql("""
                        SELECT b.* FROM utility_bill b
                          JOIN utility_bill_meter bm ON bm.bill_id = b.uuid AND bm.deleted_at IS NULL
                         WHERE bm.main_id = :mainId AND b.deleted_at IS NULL AND b.uuid IS DISTINCT FROM :except
                           AND b.period_start < :periodEnd AND :periodStart < b.period_end
                         ORDER BY b.period_end DESC
                        """)
                .param("mainId", mainId).param("except", except)
                .param("periodStart", periodStart).param("periodEnd", periodEnd)
                .query(UtilityBill.class).list();
    }

    public Optional<UtilityBill> patchBill(UUID uuid, Map<String, Object> changes) {
        return patch(BILL, BILL_COLUMNS, uuid, changes, true, UtilityBill.class);
    }

    // ── The mains a bill is for ──

    public UtilityBillMeter insertMeter(UUID billId, UUID mainId, UUID propertyId, UUID priorReadId, UUID currentReadId,
                                        BigDecimal printedUsage, MeterUnit printedUnit) {
        return jdbc.sql("""
                        INSERT INTO utility_bill_meter (bill_id, main_id, property_id, prior_read_id, current_read_id,
                                                        printed_usage, printed_unit)
                        VALUES (:billId, :mainId, :propertyId, :priorReadId, :currentReadId,
                                :printedUsage, CAST(:printedUnit AS meter_unit))
                        RETURNING *
                        """)
                .param("billId", billId).param("mainId", mainId).param("propertyId", propertyId)
                .param("priorReadId", priorReadId).param("currentReadId", currentReadId)
                .param("printedUsage", printedUsage).param("printedUnit", printedUnit == null ? null : printedUnit.name())
                .query(UtilityBillMeter.class).single();
    }

    public Optional<UtilityBillMeter> findMeter(UUID uuid) {
        return jdbc.sql("SELECT * FROM utility_bill_meter WHERE uuid = :uuid AND deleted_at IS NULL FOR UPDATE")
                .param("uuid", uuid).query(UtilityBillMeter.class).optional();
    }

    public Optional<UtilityBillMeter> findAnyMeter(UUID uuid) {
        return jdbc.sql("SELECT * FROM utility_bill_meter WHERE uuid = :uuid")
                .param("uuid", uuid).query(UtilityBillMeter.class).optional();
    }

    public List<UtilityBillMeter> metersOf(UUID billId) {
        return jdbc.sql("SELECT * FROM utility_bill_meter WHERE bill_id = :billId AND deleted_at IS NULL ORDER BY created_at")
                .param("billId", billId).query(UtilityBillMeter.class).list();
    }

    public boolean mainIsOnBill(UUID billId, UUID mainId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM utility_bill_meter WHERE bill_id = :billId AND main_id = :mainId AND deleted_at IS NULL)")
                .param("billId", billId).param("mainId", mainId).query(Boolean.class).single();
    }

    // Whether every read this bill meter names is still live (a restore must not bring back a bill without its reads)
    public boolean readsLive(UtilityBillMeter meter) {
        return jdbc.sql("""
                        SELECT NOT EXISTS (SELECT 1 FROM meter_read
                                            WHERE uuid IN (:prior, :current) AND deleted_at IS NOT NULL)
                        """)
                .param("prior", meter.priorReadId()).param("current", meter.currentReadId())
                .query(Boolean.class).single();
    }

    public Optional<UtilityBillMeter> patchMeter(UUID uuid, Map<String, Object> changes) {
        return patch(METER, METER_COLUMNS, uuid, changes, false, UtilityBillMeter.class);
    }

    // ── Lines ──

    public UtilityBillLine insertLine(UUID billId, UtilityService service, String description, BigDecimal amount,
                                      BigDecimal printedRate, MeterUnit rateUnit, boolean passedOn,
                                      LocalDate billingMonth, Integer sortOrder) {
        return jdbc.sql("""
                        INSERT INTO utility_bill_line (bill_id, service, description, amount, printed_rate, rate_unit,
                                                       passed_on, billing_month, sort_order)
                        VALUES (:billId, CAST(:service AS utility_service), :description, :amount, :printedRate,
                                CAST(:rateUnit AS meter_unit), :passedOn, :billingMonth,
                                COALESCE(:sortOrder, (SELECT COALESCE(MAX(sort_order), 0) + 1
                                                        FROM utility_bill_line WHERE bill_id = :billId)))
                        RETURNING *
                        """)
                .param("billId", billId).param("service", service.name()).param("description", description)
                .param("amount", amount).param("printedRate", printedRate)
                .param("rateUnit", rateUnit == null ? null : rateUnit.name())
                .param("passedOn", passedOn).param("billingMonth", billingMonth).param("sortOrder", sortOrder)
                .query(UtilityBillLine.class).single();
    }

    public Optional<UtilityBillLine> findLine(UUID uuid) {
        return jdbc.sql("SELECT * FROM utility_bill_line WHERE uuid = :uuid AND deleted_at IS NULL FOR UPDATE")
                .param("uuid", uuid).query(UtilityBillLine.class).optional();
    }

    public Optional<UtilityBillLine> findAnyLine(UUID uuid) {
        return jdbc.sql("SELECT * FROM utility_bill_line WHERE uuid = :uuid")
                .param("uuid", uuid).query(UtilityBillLine.class).optional();
    }

    public List<UtilityBillLine> linesOf(UUID billId, boolean deleted) {
        return jdbc.sql("SELECT * FROM utility_bill_line WHERE bill_id = :billId AND (deleted_at IS NOT NULL) = :deleted ORDER BY sort_order, created_at")
                .param("billId", billId).param("deleted", deleted).query(UtilityBillLine.class).list();
    }

    public List<UtilityBillMeter> deletedMetersOf(UUID billId) {
        return jdbc.sql("SELECT * FROM utility_bill_meter WHERE bill_id = :billId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC")
                .param("billId", billId).query(UtilityBillMeter.class).list();
    }

    public Optional<UtilityBillLine> patchLine(UUID uuid, Map<String, Object> changes) {
        return patch(LINE, LINE_COLUMNS, uuid, changes, false, UtilityBillLine.class);
    }

    // The property behind a bill, a bill meter or a line, deleted or not
    public Optional<UUID> propertyOfBill(UUID billId) {
        return findAnyBill(billId).map(UtilityBill::propertyId);
    }

    public Optional<UUID> propertyOfLine(UUID lineId) {
        return jdbc.sql("SELECT b.property_id FROM utility_bill_line l JOIN utility_bill b ON b.uuid = l.bill_id WHERE l.uuid = :uuid")
                .param("uuid", lineId).query(UUID.class).optional();
    }

    // ── Soft delete, shared by the three tables ──

    public boolean softDelete(String table, UUID uuid) {
        requireTable(table);
        return jdbc.sql("UPDATE " + table + " SET deleted_at = now() WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid).update() > 0;
    }

    public boolean restore(String table, UUID uuid) {
        requireTable(table);
        return jdbc.sql("UPDATE " + table + " SET deleted_at = NULL WHERE uuid = :uuid AND deleted_at IS NOT NULL")
                .param("uuid", uuid).update() > 0;
    }

    private static void requireTable(String table) {
        if (!Set.of(BILL, METER, LINE).contains(table)) throw new IllegalArgumentException("Not a bill table: " + table);
    }

    private <T> Optional<T> patch(String table, Set<String> allowed, UUID uuid, Map<String, Object> changes,
                                  boolean touchUpdatedAt, Class<T> type) {
        if (changes.isEmpty()) {
            return jdbc.sql("SELECT * FROM " + table + " WHERE uuid = :uuid AND deleted_at IS NULL")
                    .param("uuid", uuid).query(type).optional();
        }
        for (String col : changes.keySet()) {
            if (!allowed.contains(col)) throw new IllegalArgumentException("Invalid column: " + col);
        }
        Map<String, Object> params = new HashMap<>();
        changes.forEach((col, val) -> params.put(col, val instanceof Enum<?> e ? e.name() : val));
        params.put("uuid", uuid);
        return jdbc.sql(Patches.updateSql(table, changes, CASTS, touchUpdatedAt))
                .params(params).query(type).optional();
    }
}

package io.github.lordship.tenancyterms.internal;

import io.github.lordship.tenancyterms.ChargeTermConfiguration;
import io.github.lordship.tenancyterms.RentHistoryYear;
import io.github.lordship.tenancyterms.TenancyTermStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class TenancyChargeTermRepository {

    // Set once at creation and never patched: tenancy, agreement_type, source,
    // source_uuid, terms_template, correction_reason, created_by, created_at.
    // status and the cancel columns change only through the methods below.
    private static final Set<String> PATCHABLE_COLUMNS = Set.of(
            "valid_at", "rate",
            "car_fee", "allowed_cars", "cars_max",
            "pet_fee", "allowed_pets",
            "payment_due_day", "grace_period_days",
            "rule_violation_fee_method", "rule_violation_fee_amount",
            "nsf_fee_method", "nsf_fee_amount",
            "late_fee_method", "late_fee_amount",
            "water_method", "water_flat_amount",
            "power_method", "power_flat_amount",
            "sewer_method", "sewer_flat_amount",
            "trash_method", "trash_flat_amount",
            "security_deposit_method", "security_deposit_amount",
            "note"
    );

    private final JdbcClient jdbc;
    private final TenancyChargeTermRowMapper rowMapper;

    public TenancyChargeTermRepository(JdbcClient jdbc, TenancyChargeTermRowMapper tenancyChargeTermRowMapper) {
        this.jdbc = jdbc;
        this.rowMapper = tenancyChargeTermRowMapper;
    }

    /**
     * The distinct shapes of deal in force at one property, and how many tenancies
     * have each. Only the columns a clause can branch on -- figures never decide
     * whether a paragraph prints, so six thousand lots collapse to a handful of rows.
     *
     * <p>In force means the latest ACTIVE term whose valid_at has arrived, per
     * tenancy: DISTINCT ON picks it, so a superseded term does not count twice.
     */
    public List<ChargeTermConfiguration> findConfigurationsInForceByProperty(UUID propertyId) {
        return jdbc.sql("""
            WITH in_force AS (
                SELECT DISTINCT ON (t.tenancy) t.*
                  FROM tenancy_charge_term t
                  JOIN tenancy ten ON ten.uuid = t.tenancy AND ten.deleted_at IS NULL
                  JOIN lot l       ON l.uuid = ten.lot_id  AND l.deleted_at IS NULL
                 WHERE l.property_id = :propertyId
                   AND t.status = 'ACTIVE'
                   AND t.deleted_at IS NULL
                   AND t.valid_at <= CURRENT_DATE
                 ORDER BY t.tenancy, t.valid_at DESC
            )
            SELECT CAST(agreement_type AS text) AS agreement_type,
                   late_fee_method, nsf_fee_method, rule_violation_fee_method,
                   water_method, power_method, sewer_method, trash_method,
                   security_deposit_method,
                   COUNT(*)::int AS tenancy_count
              FROM in_force
             GROUP BY 1, 2, 3, 4, 5, 6, 7, 8, 9
             ORDER BY tenancy_count DESC
            """)
                .param("propertyId", propertyId)
                .query(ChargeTermConfiguration.class)
                .list();
    }

    /**
     * The highest rent charged for one lot in each year of a range, across
     * every tenancy that held it. The RCW 59.20 disclosure.
     *
     * <p>Not a GROUP BY on the year of valid_at: that finds only the years a
     * new term STARTED. A rate set in 2021 and left alone until 2024 was
     * charged in 2022 and 2023 too, and grouping on valid_at would disclose
     * those years as unknown while the ledger plainly knows them.
     *
     * <p>So each term is given the span it was actually in force for -- from
     * its own valid_at until the next one supersedes it -- and then matched
     * against every year in the range it overlaps. LEAD partitions by lot
     * rather than by tenancy because the disclosure is about the ground, not
     * about who was standing on it.
     *
     * <p>Only ACTIVE terms. A proposal nobody signed and a term that was
     * retracted were never charged to anyone.
     */
    public List<RentHistoryYear> findRentHistoryByLot(UUID lotId, int fromYear, int toYear) {
        return jdbc.sql("""
            WITH in_force AS (
                SELECT t.rate,
                       t.valid_at,
                       LEAD(t.valid_at) OVER (ORDER BY t.valid_at) AS superseded_at
                  FROM tenancy_charge_term t
                  JOIN tenancy ten ON ten.uuid = t.tenancy AND ten.deleted_at IS NULL
                 WHERE ten.lot_id = :lotId
                   AND t.status = 'ACTIVE'
                   AND t.deleted_at IS NULL
            )
            SELECT y AS year, MAX(f.rate) AS highest_rate
              FROM generate_series(:fromYear, :toYear) AS y
              JOIN in_force f
                ON f.valid_at < make_date(y + 1, 1, 1)
               AND (f.superseded_at IS NULL OR f.superseded_at > make_date(y, 1, 1))
             GROUP BY y
             ORDER BY y
            """)
                .param("lotId", lotId)
                .param("fromYear", fromYear)
                .param("toYear", toYear)
                .query(RentHistoryYear.class)
                .list();
    }

    /**
     * Every charge term written for one document, earliest first.
     * Works before the document is signed, which is how preview reads them.
     */
    public List<TenancyChargeTermRow> findBySource(UUID sourceUuid) {
        return jdbc.sql("""
                        SELECT * FROM tenancy_charge_term
                         WHERE source_uuid = :sourceUuid AND deleted_at IS NULL
                         ORDER BY valid_at
                        """)
                .param("sourceUuid", sourceUuid)
                .query(rowMapper)
                .list();
    }

    // Note: the copy from a terms_template IS the create.
    // Build the row with TenancyChargeTermRow.fromTemplate.
    public TenancyChargeTermRow save(TenancyChargeTermRow row) {
        return jdbc.sql("""
                INSERT INTO tenancy_charge_term (
                    tenancy, valid_at, agreement_type,
                    rate, car_fee, allowed_cars, cars_max, pet_fee, allowed_pets,
                    payment_due_day, grace_period_days,
                    rule_violation_fee_method, rule_violation_fee_amount,
                    nsf_fee_method, nsf_fee_amount,
                    late_fee_method, late_fee_amount,
                    water_method, water_flat_amount,
                    power_method, power_flat_amount,
                    sewer_method, sewer_flat_amount,
                    trash_method, trash_flat_amount,
                    security_deposit_method, security_deposit_amount,
                    status, source, source_uuid, terms_template, correction_reason,
                    note, created_by
                ) VALUES (
                    :tenancy, :validAt, :agreementType::agreement_type,
                    :rate, :carFee, :allowedCars, :carsMax, :petFee, :allowedPets,
                    :paymentDueDay, :gracePeriodDays,
                    :ruleViolationFeeMethod, :ruleViolationFeeAmount,
                    :nsfFeeMethod, :nsfFeeAmount,
                    :lateFeeMethod, :lateFeeAmount,
                    :waterMethod, :waterFlatAmount,
                    :powerMethod, :powerFlatAmount,
                    :sewerMethod, :sewerFlatAmount,
                    :trashMethod, :trashFlatAmount,
                    :securityDepositMethod, :securityDepositAmount,
                    :status, :source, :sourceUuid, :termsTemplate, :correctionReason,
                    :note, :createdBy
                ) RETURNING *
                """)
                .param("tenancy", row.tenancy())
                .param("validAt", row.validAt())
                .param("agreementType", nameOf(row.agreementType()))
                .param("rate", row.rate())
                .param("carFee", row.carFee())
                .param("allowedCars", row.allowedCars())
                .param("carsMax", row.carsMax())
                .param("petFee", row.petFee())
                .param("allowedPets", row.allowedPets())
                .param("paymentDueDay", row.paymentDueDay())
                .param("gracePeriodDays", row.gracePeriodDays())
                .param("ruleViolationFeeMethod", nameOf(row.ruleViolationFeeMethod()))
                .param("ruleViolationFeeAmount", row.ruleViolationFeeAmount())
                .param("nsfFeeMethod", nameOf(row.nsfFeeMethod()))
                .param("nsfFeeAmount", row.nsfFeeAmount())
                .param("lateFeeMethod", nameOf(row.lateFeeMethod()))
                .param("lateFeeAmount", row.lateFeeAmount())
                .param("waterMethod", nameOf(row.waterMethod()))
                .param("waterFlatAmount", row.waterFlatAmount())
                .param("powerMethod", nameOf(row.powerMethod()))
                .param("powerFlatAmount", row.powerFlatAmount())
                .param("sewerMethod", nameOf(row.sewerMethod()))
                .param("sewerFlatAmount", row.sewerFlatAmount())
                .param("trashMethod", nameOf(row.trashMethod()))
                .param("trashFlatAmount", row.trashFlatAmount())
                .param("securityDepositMethod", nameOf(row.securityDepositMethod()))
                .param("securityDepositAmount", row.securityDepositAmount())
                .param("status", nameOf(row.status()))
                .param("source", nameOf(row.source()))
                .param("sourceUuid", row.sourceUuid())
                .param("termsTemplate", row.termsTemplate())
                .param("correctionReason", row.correctionReason())
                .param("note", row.note())
                .param("createdBy", row.createdBy())
                .query(rowMapper)
                .single();
    }

    public Optional<TenancyChargeTermRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM tenancy_charge_term WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    // The deal history for one tenancy, newest first.
    public List<TenancyChargeTermRow> findByTenancy(UUID tenancy) {
        return jdbc.sql("""
                SELECT * FROM tenancy_charge_term
                WHERE tenancy = :tenancy AND deleted_at IS NULL
                ORDER BY valid_at DESC, uuid DESC
                """)
                .param("tenancy", tenancy)
                .query(rowMapper)
                .list();
    }

    // note this is just extra defense layered on top of the "tenancy_charge_term_in_force_uq" in V1
    public Optional<TenancyChargeTermRow> findInForceOn(UUID tenancy, LocalDate on) {
        return jdbc.sql("""
                SELECT * FROM tenancy_charge_term
                WHERE tenancy = :tenancy
                  AND status = 'ACTIVE'
                  AND valid_at <= :on
                  AND deleted_at IS NULL
                ORDER BY valid_at DESC, uuid DESC
                LIMIT 1
                """)
                .param("tenancy", tenancy)
                .param("on", on)
                .query(rowMapper)
                .optional();
    }

    // Plain CRUD: whether the term is still editable is the service's call, so
    // that a locked term answers 400 rather than being mistaken for missing.
    public Optional<TenancyChargeTermRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE tenancy_charge_term SET ");

        changes.forEach((col, val) -> sql.append(col).append(" = :").append(col).append(", "));

        // trim trailing comma and space
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(rowMapper)
                .optional();
    }

    // Guarded transition. Empty means the row was not in the expected state --
    // which is also what stops two agents from submitting the same term twice.
    // The escaped CHECK constraints re-evaluate on this UPDATE, so a PROPOSED
    // row with inconsistent pairs fails here; the service validates first.
    public Optional<TenancyChargeTermRow> updateStatus(UUID uuid,
                                                       TenancyTermStatus from,
                                                       TenancyTermStatus to) {
        return jdbc.sql("""
                UPDATE tenancy_charge_term
                SET status = :to
                WHERE uuid = :uuid AND status = :from AND deleted_at IS NULL
                RETURNING *
                """)
                .param("uuid", uuid)
                .param("from", nameOf(from))
                .param("to", nameOf(to))
                .query(rowMapper)
                .optional();
    }

    // Cancelling ends a term that HAS gone into effect, so it is guarded on
    // ACTIVE. All four columns move in one UPDATE because term_cancel_facts and
    // term_cancel_fields_only_when_cancelled would both fail on a partial write.
    public Optional<TenancyChargeTermRow> cancel(UUID uuid, UUID cancelledBy, String cancelReason) {
        return jdbc.sql("""
                UPDATE tenancy_charge_term
                SET status = 'CANCELLED',
                    cancelled_at = now(),
                    cancelled_by = :cancelledBy,
                    cancel_reason = :cancelReason
                WHERE uuid = :uuid AND status = 'ACTIVE' AND deleted_at IS NULL
                RETURNING *
                """)
                .param("uuid", uuid)
                .param("cancelledBy", cancelledBy)
                .param("cancelReason", cancelReason)
                .query(rowMapper)
                .optional();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("""
                UPDATE tenancy_charge_term
                SET deleted_at = now()
                WHERE uuid = :uuid
                  AND status IN ('PROPOSED','PENDING')
                  AND deleted_at IS NULL
                """)
                .param("uuid", uuid)
                .update() > 0;
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
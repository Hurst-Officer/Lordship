package io.github.lordship.tenancyterms.internal;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.tenancyterms.ChargeTermConfiguration;
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

    // tenancy, agreement_type, terms_template, batch, created_by and created_at
    // are set once at creation. status, source_uuid and the cancel columns move
    // through the transition methods below, never through PATCH.
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
                    status, source, source_uuid, terms_template, batch,
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
                    :status, :source, :sourceUuid, :termsTemplate, :batch,
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
                .param("batch", row.batch())
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

    // One bulk run, so it can be reviewed or abandoned together.
    public List<TenancyChargeTermRow> findByBatch(UUID batch) {
        return jdbc.sql("""
                SELECT * FROM tenancy_charge_term
                WHERE batch = :batch AND deleted_at IS NULL
                ORDER BY valid_at DESC, uuid DESC
                """)
                .param("batch", batch)
                .query(rowMapper)
                .list();
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

    // The instrument that produced this deal. Separate from PATCH because of the
    // composite FK to instrument(uuid, tenancy): a document from another tenancy
    // cannot be attached, and the database is what enforces it.
    public Optional<TenancyChargeTermRow> attachSource(UUID uuid, UUID sourceUuid) {
        return jdbc.sql("""
                UPDATE tenancy_charge_term
                SET source_uuid = :sourceUuid
                WHERE uuid = :uuid AND deleted_at IS NULL
                RETURNING *
                """)
                .param("uuid", uuid)
                .param("sourceUuid", sourceUuid)
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
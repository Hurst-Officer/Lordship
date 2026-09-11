package io.github.lordship.termstemplate.internal;

import io.github.lordship.shared.AgreementType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class TermsTemplateRepository {

    // property, agreement_type, copied_from and created_by not included - they should not be changed
    private static final Set<String> PATCHABLE_COLUMNS = Set.of(
            "name", "target_rate", "asking_rate",
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
    private final TermsTemplateRowMapper rowMapper;

    public TermsTemplateRepository(JdbcClient jdbc, TermsTemplateRowMapper termsTemplateRowMapper) {
        this.jdbc = jdbc;
        this.rowMapper = termsTemplateRowMapper;
    }

    // Creates a blank set -- every term column takes its DB default.
    public TermsTemplateRow save(TermsTemplateRow row) {
        return jdbc.sql("""
                INSERT INTO terms_template (property, name, agreement_type, created_by)
                VALUES (:property, :name, :agreementType::agreement_type, :createdBy)
                RETURNING *
                """)
                .param("property", row.property())
                .param("name", row.name())
                .param("agreementType", nameOf(row.agreementType()))
                .param("createdBy", row.createdBy())
                .query(rowMapper)
                .single();
    }

    // copy a template into a property.
    public TermsTemplateRow saveCopy(TermsTemplateRow row) {
        return jdbc.sql("""
                INSERT INTO terms_template (
                    property, copied_from, name, agreement_type, target_rate, asking_rate,
                    car_fee, allowed_cars, cars_max, pet_fee, allowed_pets,
                    payment_due_day, grace_period_days,
                    rule_violation_fee_method, rule_violation_fee_amount,
                    nsf_fee_method, nsf_fee_amount,
                    late_fee_method, late_fee_amount,
                    water_method, water_flat_amount,
                    power_method, power_flat_amount,
                    sewer_method, sewer_flat_amount,
                    trash_method, trash_flat_amount,
                    security_deposit_method, security_deposit_amount,
                    note, created_by
                ) VALUES (
                    :property, :copiedFrom, :name, :agreementType::agreement_type, :targetRate, :askingRate,
                    :carFee, :allowedCars, :carsMax, :petFee, :allowedPets,
                    :paymentDueDay, :gracePeriodDays,
                    :ruleViolationFeeMethod, :ruleViolationFeeAmount,
                    :nsfFeeMethod, :nsfFeeAmount,
                    :lateFeeMethod, :lateFeeAmount,
                    :waterMethod, :waterFlatAmount,
                    :powerMethod, :powerFlatAmount,
                    :sewerMethod, :sewerFlatAmount,
                    :trashMethod, :trashFlatAmount,
                    :securityDepositMethod, :securityDepositAmount,
                    :note, :createdBy
                ) RETURNING *
                """)
                .param("property", row.property())
                .param("copiedFrom", row.copiedFrom())
                .param("name", row.name())
                .param("agreementType", nameOf(row.agreementType()))
                .param("targetRate", row.targetRate())
                .param("askingRate", row.askingRate())
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
                .param("note", row.note())
                .param("createdBy", row.createdBy())
                .query(rowMapper)
                .single();
    }

    public Optional<TermsTemplateRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM terms_template WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    // The deal types this property may offer, in enum declaration order.
    public List<TermsTemplateRow> findByProperty(UUID property) {
        return jdbc.sql("""
                SELECT * FROM terms_template
                WHERE property = :property AND deleted_at IS NULL
                ORDER BY agreement_type
                """)
                .param("property", property)
                .query(rowMapper)
                .list();
    }

    // Admin-only templates: the pool a property copies from.
    public List<TermsTemplateRow> findGlobalTemplates() {
        return jdbc.sql("""
                SELECT * FROM terms_template
                WHERE property IS NULL AND deleted_at IS NULL
                ORDER BY agreement_type
                """)
                .query(rowMapper)
                .list();
    }

    // Property-scoped only. Global templates may have several per agreement type,
    // so they are looked up by name instead.
    public Optional<TermsTemplateRow> findByPropertyAndAgreementType(UUID property, AgreementType agreementType) {
        return jdbc.sql("""
            SELECT * FROM terms_template
            WHERE property = :property
              AND agreement_type = :agreementType::agreement_type
              AND deleted_at IS NULL
            """)
                .param("property", property)
                .param("agreementType", nameOf(agreementType))
                .query(rowMapper)
                .optional();
    }

    public Optional<TermsTemplateRow> findGlobalByName(String name) {
        return jdbc.sql("""
            SELECT * FROM terms_template
            WHERE property IS NULL
              AND lower(name) = lower(:name)
              AND deleted_at IS NULL
            """)
                .param("name", name)
                .query(rowMapper)
                .optional();
    }

    public Optional<TermsTemplateRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE terms_template SET ");
        changes.forEach((col, val) -> sql.append(col).append(" = :").append(col).append(", "));
        sql.append("updated_at = now()");
        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(rowMapper)
                .optional();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("UPDATE terms_template SET deleted_at = now() WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .update() > 0;
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
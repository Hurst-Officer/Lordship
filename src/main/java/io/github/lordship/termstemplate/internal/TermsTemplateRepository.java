package io.github.lordship.termstemplate.internal;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.ChargeKind;
import io.github.lordship.shared.LateFeeTier;
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

    // property, agreement_type, state, copied_from and created_by not included - they should not be changed
    private static final Set<String> PATCHABLE_COLUMNS = Set.of(
            "name", "target_rate", "asking_rate",
            "escalation_percent", "escalation_months",
            "car_fee", "allowed_cars", "cars_max",
            "pet_fee", "allowed_pets",
            "payment_due_day", "grace_period_days",
            "rule_violation_fee_method", "rule_violation_fee_amount",
            "nsf_fee_method", "nsf_fee_amount",
            "late_fee_method", "late_fee_amount", "late_fee_tiers",
            "water_method", "water_flat_amount",
            "power_method", "power_flat_amount",
            "sewer_method", "sewer_flat_amount",
            "trash_method", "trash_flat_amount",
            "security_deposit_method", "security_deposit_amount",
            "payment_order", "rent_kinds", "rent_starts",
            "note"
    );

    private final JdbcClient jdbc;
    private final TermsTemplateRowMapper rowMapper;

    public TermsTemplateRepository(JdbcClient jdbc, TermsTemplateRowMapper termsTemplateRowMapper) {
        this.jdbc = jdbc;
        this.rowMapper = termsTemplateRowMapper;
    }

    // Creates a blank set -- every term column takes its DB default, apart from the state and the two rules a
    // state's law starts a template with (payment order, Counts as rent).
    public TermsTemplateRow save(TermsTemplateRow row) {
        return jdbc.sql("""
                INSERT INTO terms_template (property, name, agreement_type, created_by,
                                            payment_order, rent_kinds, state)
                VALUES (:property, :name, :agreementType::agreement_type, :createdBy,
                        :paymentOrder, CAST(:rentKinds AS charge_kind[]), :state)
                RETURNING *
                """)
                .param("property", row.property())
                .param("name", row.name())
                .param("agreementType", nameOf(row.agreementType()))
                .param("createdBy", row.createdBy())
                .param("paymentOrder", nameOf(row.paymentOrder()))
                .param("rentKinds", ChargeKind.toDatabase(row.rentKinds()))
                .param("state", row.state())
                .query(rowMapper)
                .single();
    }

    // copy a template into a property.
    public TermsTemplateRow saveCopy(TermsTemplateRow row) {
        return jdbc.sql("""
                INSERT INTO terms_template (
                    property, copied_from, name, agreement_type, target_rate, asking_rate,
                    escalation_percent, escalation_months,
                    car_fee, allowed_cars, cars_max, pet_fee, allowed_pets,
                    payment_due_day, grace_period_days,
                    rule_violation_fee_method, rule_violation_fee_amount,
                    nsf_fee_method, nsf_fee_amount,
                    late_fee_method, late_fee_amount, late_fee_tiers,
                    water_method, water_flat_amount,
                    power_method, power_flat_amount,
                    sewer_method, sewer_flat_amount,
                    trash_method, trash_flat_amount,
                    security_deposit_method, security_deposit_amount,
                    payment_order, rent_kinds, rent_starts, state,
                    note, created_by
                ) VALUES (
                    :property, :copiedFrom, :name, :agreementType::agreement_type, :targetRate, :askingRate,
                    :escalationPercent, :escalationMonths,
                    :carFee, :allowedCars, :carsMax, :petFee, :allowedPets,
                    :paymentDueDay, :gracePeriodDays,
                    :ruleViolationFeeMethod, :ruleViolationFeeAmount,
                    :nsfFeeMethod, :nsfFeeAmount,
                    :lateFeeMethod, :lateFeeAmount, CAST(:lateFeeTiers AS jsonb),
                    :waterMethod, :waterFlatAmount,
                    :powerMethod, :powerFlatAmount,
                    :sewerMethod, :sewerFlatAmount,
                    :trashMethod, :trashFlatAmount,
                    :securityDepositMethod, :securityDepositAmount,
                    :paymentOrder, CAST(:rentKinds AS charge_kind[]), :rentStarts, :state,
                    :note, :createdBy
                ) RETURNING *
                """)
                .param("property", row.property())
                .param("copiedFrom", row.copiedFrom())
                .param("name", row.name())
                .param("agreementType", nameOf(row.agreementType()))
                .param("targetRate", row.targetRate())
                .param("askingRate", row.askingRate())
                .param("escalationPercent", row.escalationPercent())
                .param("escalationMonths", row.escalationMonths())
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
                .param("lateFeeTiers", LateFeeTier.toJson(row.lateFeeTiers()))
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
                .param("paymentOrder", nameOf(row.paymentOrder()))
                .param("rentKinds", ChargeKind.toDatabase(row.rentKinds()))
                .param("rentStarts", nameOf(row.rentStarts()))
                .param("state", row.state())
                .param("note", row.note())
                .param("createdBy", row.createdBy())
                .query(rowMapper)
                .single();
    }

    // The state a property is in (property.property_state), whose law the property's deals follow.
    // Empty for an unknown property and for a deleted property, so a template is never copied into a deleted property.
    public Optional<String> findPropertyState(UUID property) {
        return jdbc.sql("SELECT property_state FROM property WHERE uuid = :property AND deleted_at IS NULL")
                .param("property", property)
                .query(String.class)
                .optional();
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

    // Every live property's templates (the deals the properties offer), of every agreement type.
    // For the screen that lists the deals of all properties and changes several at once.
    public List<TermsTemplateRow> findPropertyTemplates() {
        return jdbc.sql("""
                SELECT t.* FROM terms_template t
                JOIN property p ON p.uuid = t.property
                WHERE t.deleted_at IS NULL AND p.deleted_at IS NULL
                ORDER BY t.agreement_type, t.uuid
                """)
                .query(rowMapper)
                .list();
    }

    // Deleted global templates, the most recently deleted first
    public List<TermsTemplateRow> findDeletedGlobalTemplates() {
        return jdbc.sql("""
                SELECT * FROM terms_template
                WHERE property IS NULL AND deleted_at IS NOT NULL
                ORDER BY deleted_at DESC, uuid
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
        for (String col : changes.keySet()) {
            if (col.equals("late_fee_tiers")) {
                // The service hands over a List<LateFeeTier>. It is stored as JSONB.
                sql.append(col).append(" = CAST(:").append(col).append(" AS jsonb), ");
            } else if (col.equals("rent_kinds")) {
                // The service hands over a List<ChargeKind>. It is stored as a charge_kind[].
                sql.append(col).append(" = CAST(:").append(col).append(" AS charge_kind[]), ");
            } else {
                sql.append(col).append(" = :").append(col).append(", ");
            }
        }
        sql.append("updated_at = now()");
        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);
        if (params.get("late_fee_tiers") instanceof List<?> tiers) {
            params.put("late_fee_tiers", LateFeeTier.toJson(
                    tiers.stream().map(LateFeeTier.class::cast).toList()));
        }
        if (params.get("rent_kinds") instanceof List<?> kinds) {
            params.put("rent_kinds", ChargeKind.toDatabase(kinds));
        }

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

    // A template whether the template is deleted or not
    public Optional<TermsTemplateRow> findAnyById(UUID uuid) {
        return jdbc.sql("SELECT * FROM terms_template WHERE uuid = :uuid")
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    // Brings a deleted template back. Empty when the template was not deleted.
    public Optional<TermsTemplateRow> restore(UUID uuid) {
        return jdbc.sql("UPDATE terms_template SET deleted_at = NULL WHERE uuid = :uuid AND deleted_at IS NOT NULL RETURNING *")
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
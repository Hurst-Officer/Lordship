package io.github.lordship.meterbills.internal;

import io.github.lordship.meterbills.UtilityCycle;
import io.github.lordship.meterbills.UtilityService;
import io.github.lordship.shared.Patches;
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
public class UtilityCycleRepository {

    public static final String TABLE = "utility_cycle";
    public static final Set<String> COLUMNS = Set.of(
            "cycle_months", "anchor_month", "lag_months", "read_day", "effective_from", "notes");

    private final JdbcClient jdbc;

    public UtilityCycleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UtilityCycle insert(UUID propertyId, UUID rateGroupId, UtilityService service, int cycleMonths, int anchorMonth,
                               int lagMonths, Integer readDay, LocalDate effectiveFrom, String notes) {
        return jdbc.sql("""
                        INSERT INTO utility_cycle (property_id, rate_group_id, service, cycle_months, anchor_month,
                                                   lag_months, read_day, effective_from, notes)
                        VALUES (:propertyId, :rateGroupId, CAST(:service AS utility_service), :cycleMonths, :anchorMonth,
                                :lagMonths, :readDay, :effectiveFrom, :notes)
                        RETURNING *
                        """)
                .param("propertyId", propertyId).param("rateGroupId", rateGroupId).param("service", service.name())
                .param("cycleMonths", cycleMonths).param("anchorMonth", anchorMonth).param("lagMonths", lagMonths)
                .param("readDay", readDay).param("effectiveFrom", effectiveFrom).param("notes", notes)
                .query(UtilityCycle.class).single();
    }

    public Optional<UtilityCycle> find(UUID uuid) {
        return jdbc.sql("SELECT * FROM utility_cycle WHERE uuid = :uuid AND deleted_at IS NULL FOR UPDATE")
                .param("uuid", uuid).query(UtilityCycle.class).optional();
    }

    public Optional<UtilityCycle> findAny(UUID uuid) {
        return jdbc.sql("SELECT * FROM utility_cycle WHERE uuid = :uuid")
                .param("uuid", uuid).query(UtilityCycle.class).optional();
    }

    public List<UtilityCycle> of(UUID propertyId, boolean deleted) {
        return jdbc.sql("SELECT * FROM utility_cycle WHERE property_id = :propertyId AND (deleted_at IS NOT NULL) = :deleted ORDER BY service, rate_group_id NULLS FIRST, effective_from")
                .param("propertyId", propertyId).param("deleted", deleted).query(UtilityCycle.class).list();
    }

    // The cycle in force for a service on a day: the live one that started most recently on or before it.
    // rateGroupId null asks for the park's own cycle; a group's cycle is never mixed with it here.
    public Optional<UtilityCycle> inForce(UUID propertyId, UUID rateGroupId, UtilityService service, LocalDate on) {
        return jdbc.sql("""
                        SELECT * FROM utility_cycle
                         WHERE property_id = :propertyId AND service = CAST(:service AS utility_service)
                           AND rate_group_id IS NOT DISTINCT FROM CAST(:rateGroupId AS uuid)
                           AND effective_from <= :on AND deleted_at IS NULL
                         ORDER BY effective_from DESC LIMIT 1
                        """)
                .param("propertyId", propertyId).param("rateGroupId", rateGroupId)
                .param("service", service.name()).param("on", on)
                .query(UtilityCycle.class).optional();
    }

    public boolean startTaken(UUID propertyId, UUID rateGroupId, UtilityService service, LocalDate effectiveFrom, UUID except) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM utility_cycle
                                        WHERE property_id = :propertyId AND service = CAST(:service AS utility_service)
                                          AND rate_group_id IS NOT DISTINCT FROM CAST(:rateGroupId AS uuid)
                                          AND effective_from = :from AND deleted_at IS NULL
                                          AND uuid IS DISTINCT FROM CAST(:except AS uuid))
                        """)
                .param("propertyId", propertyId).param("rateGroupId", rateGroupId).param("service", service.name())
                .param("from", effectiveFrom).param("except", except).query(Boolean.class).single();
    }

    public Optional<UtilityCycle> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return find(uuid);
        for (String col : changes.keySet()) {
            if (!COLUMNS.contains(col)) throw new IllegalArgumentException("Invalid column: " + col);
        }
        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);
        return jdbc.sql(Patches.updateSql(TABLE, changes, Map.of(), false))
                .params(params).query(UtilityCycle.class).optional();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("UPDATE utility_cycle SET deleted_at = now() WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid).update() > 0;
    }

    public boolean restore(UUID uuid) {
        return jdbc.sql("UPDATE utility_cycle SET deleted_at = NULL WHERE uuid = :uuid AND deleted_at IS NOT NULL")
                .param("uuid", uuid).update() > 0;
    }
}

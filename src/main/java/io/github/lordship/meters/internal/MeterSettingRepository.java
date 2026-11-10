package io.github.lordship.meters.internal;

import io.github.lordship.meters.MeterSetting;
import io.github.lordship.meters.MeterType;
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

// How to read each meter's dial, from a day on. Exactly one of main_id, submeter_id.
@Repository
public class MeterSettingRepository {

    public static final String TABLE = "meter_setting";
    public static final Set<String> COLUMNS = Set.of("effective_from", "unit", "multiplier", "dial_max", "notes");
    private static final Map<String, String> CASTS = Map.of("unit", "meter_unit");

    private final JdbcClient jdbc;

    public MeterSettingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public MeterSetting insert(UUID mainId, UUID submeterId, UUID propertyId, MeterType type, LocalDate effectiveFrom,
                               MeterUnit unit, BigDecimal multiplier, long dialMax, String notes) {
        return jdbc.sql("""
                        INSERT INTO meter_setting (main_id, submeter_id, property_id, utility_type, effective_from,
                                                   unit, multiplier, dial_max, notes)
                        VALUES (:mainId, :submeterId, :propertyId, CAST(:type AS meter_type), :effectiveFrom,
                                CAST(:unit AS meter_unit), :multiplier, :dialMax, :notes)
                        RETURNING *
                        """)
                .param("mainId", mainId).param("submeterId", submeterId).param("propertyId", propertyId)
                .param("type", type.name()).param("effectiveFrom", effectiveFrom).param("unit", unit.name())
                .param("multiplier", multiplier).param("dialMax", dialMax).param("notes", notes)
                .query(MeterSetting.class).single();
    }

    public Optional<MeterSetting> findForUpdate(UUID uuid) {
        return jdbc.sql("SELECT * FROM meter_setting WHERE uuid = :uuid AND deleted_at IS NULL FOR UPDATE")
                .param("uuid", uuid).query(MeterSetting.class).optional();
    }

    public Optional<MeterSetting> findAny(UUID uuid) {
        return jdbc.sql("SELECT * FROM meter_setting WHERE uuid = :uuid")
                .param("uuid", uuid).query(MeterSetting.class).optional();
    }

    // One meter's live settings, the undated one first
    public List<MeterSetting> ofMain(UUID mainId) {
        return jdbc.sql("SELECT * FROM meter_setting WHERE main_id = :id AND deleted_at IS NULL ORDER BY effective_from NULLS FIRST")
                .param("id", mainId).query(MeterSetting.class).list();
    }

    public List<MeterSetting> ofSubmeter(UUID submeterId) {
        return jdbc.sql("SELECT * FROM meter_setting WHERE submeter_id = :id AND deleted_at IS NULL ORDER BY effective_from NULLS FIRST")
                .param("id", submeterId).query(MeterSetting.class).list();
    }

    public List<MeterSetting> ofProperty(UUID propertyId, boolean deleted) {
        return jdbc.sql("SELECT * FROM meter_setting WHERE property_id = :propertyId AND (deleted_at IS NOT NULL) = :deleted"
                        + " ORDER BY effective_from NULLS FIRST")
                .param("propertyId", propertyId).param("deleted", deleted).query(MeterSetting.class).list();
    }

    /**
     * Whether a live bill's usage for this main depends on the setting for days
     * from {@code from} (null: the start) up to {@code until} (null: on and on).
     * A bill's usage adds up every read after its old read up to its new read, each
     * worked out with the setting on that read's day; so a bill depends on the days
     * after its old read's day through its new read's day.
     */
    public boolean billedReadBetween(UUID mainId, LocalDate from, LocalDate until) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM utility_bill_meter bm
                              JOIN utility_bill b ON b.uuid = bm.bill_id AND b.deleted_at IS NULL
                              JOIN meter_read p ON p.uuid = bm.prior_read_id
                              JOIN meter_read c ON c.uuid = bm.current_read_id
                             WHERE bm.main_id = :mainId AND bm.deleted_at IS NULL
                               AND (CAST(:from AS date) IS NULL OR c.read_on >= :from)
                               AND (CAST(:until AS date) IS NULL OR p.read_on + 1 < :until))
                        """)
                .param("mainId", mainId).param("from", from).param("until", until)
                .query(Boolean.class).single();
    }

    public Optional<MeterSetting> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) {
            return jdbc.sql("SELECT * FROM meter_setting WHERE uuid = :uuid AND deleted_at IS NULL")
                    .param("uuid", uuid).query(MeterSetting.class).optional();
        }
        for (String col : changes.keySet()) {
            if (!COLUMNS.contains(col)) throw new IllegalArgumentException("Invalid column: " + col);
        }
        Map<String, Object> params = new HashMap<>();
        changes.forEach((col, val) -> params.put(col, val instanceof Enum<?> e ? e.name() : val));
        params.put("uuid", uuid);
        return jdbc.sql(Patches.updateSql(TABLE, changes, CASTS, false))
                .params(params).query(MeterSetting.class).optional();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("UPDATE meter_setting SET deleted_at = now() WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid).update() > 0;
    }

    public boolean restore(UUID uuid) {
        return jdbc.sql("UPDATE meter_setting SET deleted_at = NULL WHERE uuid = :uuid AND deleted_at IS NOT NULL")
                .param("uuid", uuid).update() > 0;
    }
}

package io.github.lordship.meters.internal;

import io.github.lordship.meters.MeterMain;
import io.github.lordship.meters.MeterType;
import io.github.lordship.meters.RateGroup;
import io.github.lordship.meters.Submeter;
import io.github.lordship.shared.Patches;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Mains, submeters and rate groups. Every one carries its property, which is what PropertyScope checks.
@Repository
public class MeterRepository {

    public static final String MAIN = "meter_main";
    public static final String SUBMETER = "submeter";
    public static final String RATE_GROUP = "utility_rate_group";

    public static final Set<String> MAIN_COLUMNS = Set.of(
            "rate_group_id", "title", "provider", "account_number",
            "serial_number", "point_x", "point_y", "installed_on", "removed_on", "notes");
    public static final Set<String> SUBMETER_COLUMNS = Set.of(
            "title", "serial_number", "point_x", "point_y", "installed_on", "removed_on", "notes");
    public static final Set<String> RATE_GROUP_COLUMNS = Set.of("name", "notes");

    private static final Map<String, String> CASTS = Map.of();

    private final JdbcClient jdbc;

    public MeterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── Mains ──

    public MeterMain insertMain(UUID propertyId, MeterType type, UUID rateGroupId, String title, String provider,
                                String accountNumber, String serialNumber) {
        return jdbc.sql("""
                        INSERT INTO meter_main (property_id, utility_type, rate_group_id, title, provider, account_number,
                                                serial_number)
                        VALUES (:propertyId, :type::meter_type, :rateGroupId, :title, :provider, :accountNumber,
                                :serialNumber)
                        RETURNING *
                        """)
                .param("propertyId", propertyId)
                .param("type", type.name())
                .param("rateGroupId", rateGroupId)
                .param("title", title)
                .param("provider", provider)
                .param("accountNumber", accountNumber)
                .param("serialNumber", serialNumber)
                .query(MeterMain.class)
                .single();
    }

    public Optional<MeterMain> findMain(UUID uuid) {
        return jdbc.sql("SELECT * FROM meter_main WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid).query(MeterMain.class).optional();
    }

    // Held until the transaction ends: a link or bill being made to it waits for a delete, and the other way round
    public Optional<MeterMain> findMainForShare(UUID uuid) {
        return jdbc.sql("SELECT * FROM meter_main WHERE uuid = :uuid AND deleted_at IS NULL FOR SHARE")
                .param("uuid", uuid).query(MeterMain.class).optional();
    }

    // Locked until the transaction ends, so two saves at once log the right "before"
    public Optional<MeterMain> findMainForUpdate(UUID uuid) {
        return jdbc.sql("SELECT * FROM meter_main WHERE uuid = :uuid AND deleted_at IS NULL FOR UPDATE")
                .param("uuid", uuid).query(MeterMain.class).optional();
    }

    public Optional<MeterMain> findAnyMain(UUID uuid) {
        return jdbc.sql("SELECT * FROM meter_main WHERE uuid = :uuid")
                .param("uuid", uuid).query(MeterMain.class).optional();
    }

    public List<MeterMain> mainsOf(UUID propertyId, boolean deleted) {
        return jdbc.sql("SELECT * FROM meter_main WHERE property_id = :propertyId AND (deleted_at IS NOT NULL) = :deleted"
                        + " ORDER BY utility_type, title")
                .param("propertyId", propertyId).param("deleted", deleted)
                .query(MeterMain.class).list();
    }

    public Optional<MeterMain> patchMain(UUID uuid, Map<String, Object> changes) {
        return patch(MAIN, MAIN_COLUMNS, uuid, changes, true, MeterMain.class);
    }

    // ── Submeters ──

    public Submeter insertSubmeter(UUID propertyId, MeterType type, String title, String serialNumber) {
        return jdbc.sql("""
                        INSERT INTO submeter (property_id, utility_type, title, serial_number)
                        VALUES (:propertyId, :type::meter_type, :title, :serialNumber)
                        RETURNING *
                        """)
                .param("propertyId", propertyId)
                .param("type", type.name())
                .param("title", title)
                .param("serialNumber", serialNumber)
                .query(Submeter.class)
                .single();
    }

    public Optional<Submeter> findSubmeter(UUID uuid) {
        return jdbc.sql("SELECT * FROM submeter WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid).query(Submeter.class).optional();
    }

    public Optional<Submeter> findSubmeterForUpdate(UUID uuid) {
        return jdbc.sql("SELECT * FROM submeter WHERE uuid = :uuid AND deleted_at IS NULL FOR UPDATE")
                .param("uuid", uuid).query(Submeter.class).optional();
    }

    public Optional<Submeter> findAnySubmeter(UUID uuid) {
        return jdbc.sql("SELECT * FROM submeter WHERE uuid = :uuid")
                .param("uuid", uuid).query(Submeter.class).optional();
    }

    public List<Submeter> submetersOf(UUID propertyId, boolean deleted) {
        return jdbc.sql("SELECT * FROM submeter WHERE property_id = :propertyId AND (deleted_at IS NOT NULL) = :deleted"
                        + " ORDER BY utility_type, title NULLS LAST, created_at")
                .param("propertyId", propertyId).param("deleted", deleted)
                .query(Submeter.class).list();
    }

    public Optional<Submeter> patchSubmeter(UUID uuid, Map<String, Object> changes) {
        return patch(SUBMETER, SUBMETER_COLUMNS, uuid, changes, true, Submeter.class);
    }

    // ── Rate groups ──

    public RateGroup insertRateGroup(UUID propertyId, String name, String notes) {
        return jdbc.sql("INSERT INTO utility_rate_group (property_id, name, notes) VALUES (:propertyId, :name, :notes) RETURNING *")
                .param("propertyId", propertyId).param("name", name).param("notes", notes)
                .query(RateGroup.class).single();
    }

    public Optional<RateGroup> findRateGroup(UUID uuid) {
        return jdbc.sql("SELECT * FROM utility_rate_group WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid).query(RateGroup.class).optional();
    }

    // Held while a main or bill is put in the group, so a delete of the group waits
    public Optional<RateGroup> findRateGroupForShare(UUID uuid) {
        return jdbc.sql("SELECT * FROM utility_rate_group WHERE uuid = :uuid AND deleted_at IS NULL FOR SHARE")
                .param("uuid", uuid).query(RateGroup.class).optional();
    }

    public Optional<RateGroup> findRateGroupForUpdate(UUID uuid) {
        return jdbc.sql("SELECT * FROM utility_rate_group WHERE uuid = :uuid AND deleted_at IS NULL FOR UPDATE")
                .param("uuid", uuid).query(RateGroup.class).optional();
    }

    public Optional<RateGroup> findAnyRateGroup(UUID uuid) {
        return jdbc.sql("SELECT * FROM utility_rate_group WHERE uuid = :uuid")
                .param("uuid", uuid).query(RateGroup.class).optional();
    }

    public List<RateGroup> rateGroupsOf(UUID propertyId, boolean deleted) {
        return jdbc.sql("SELECT * FROM utility_rate_group WHERE property_id = :propertyId AND (deleted_at IS NOT NULL) = :deleted ORDER BY name")
                .param("propertyId", propertyId).param("deleted", deleted)
                .query(RateGroup.class).list();
    }

    public boolean rateGroupNameTaken(UUID propertyId, String name, UUID except) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM utility_rate_group
                                        WHERE property_id = :propertyId AND deleted_at IS NULL
                                          AND lower(trim(name)) = lower(trim(:name))
                                          AND uuid IS DISTINCT FROM :except)
                        """)
                .param("propertyId", propertyId).param("name", name).param("except", except)
                .query(Boolean.class).single();
    }

    public Optional<RateGroup> patchRateGroup(UUID uuid, Map<String, Object> changes) {
        return patch(RATE_GROUP, RATE_GROUP_COLUMNS, uuid, changes, false, RateGroup.class);
    }

    public boolean rateGroupHasMains(UUID rateGroupId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM meter_main WHERE rate_group_id = :id AND deleted_at IS NULL)")
                .param("id", rateGroupId).query(Boolean.class).single();
    }

    // A live bill entered under the group: its rate depends on the group staying
    public boolean rateGroupHasBills(UUID rateGroupId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM utility_bill WHERE rate_group_id = :id AND deleted_at IS NULL)")
                .param("id", rateGroupId).query(Boolean.class).single();
    }

    // ── Rules that look past this module's tables ──

    // A live link that has not ended yet (or ends in the future): the meter is still in the tree
    public boolean mainHasOpenLinks(UUID mainId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM submeter_main WHERE main_id = :id AND deleted_at IS NULL"
                        + " AND (effective_to IS NULL OR effective_to > current_date))")
                .param("id", mainId).query(Boolean.class).single();
    }

    public boolean submeterHasOpenLinks(UUID submeterId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM submeter_main WHERE submeter_id = :id AND deleted_at IS NULL
                                                  AND (effective_to IS NULL OR effective_to > current_date))
                            OR EXISTS (SELECT 1 FROM submeter_lot  WHERE submeter_id = :id AND deleted_at IS NULL
                                                  AND (effective_to IS NULL OR effective_to > current_date))
                        """)
                .param("id", submeterId).query(Boolean.class).single();
    }

    // A main named on a bill that is not deleted. Read like lots reads tenancy: a check, not a dependency.
    public boolean mainIsOnBills(UUID mainId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM utility_bill_meter bm
                                         JOIN utility_bill b ON b.uuid = bm.bill_id
                                        WHERE bm.main_id = :id AND bm.deleted_at IS NULL AND b.deleted_at IS NULL)
                        """)
                .param("id", mainId).query(Boolean.class).single();
    }

    // A live lot in the property, held so it cannot be deleted while a link to it is made
    public boolean lotIsInProperty(UUID lotId, UUID propertyId) {
        return jdbc.sql("SELECT uuid FROM lot WHERE uuid = :lotId AND property_id = :propertyId AND deleted_at IS NULL FOR SHARE")
                .param("lotId", lotId).param("propertyId", propertyId).query(UUID.class).optional().isPresent();
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
        if (!Set.of(MAIN, SUBMETER, RATE_GROUP).contains(table)) {
            throw new IllegalArgumentException("Not a meter table: " + table);
        }
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
                .params(params)
                .query(type)
                .optional();
    }
}

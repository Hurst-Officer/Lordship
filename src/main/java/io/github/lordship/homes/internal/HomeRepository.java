package io.github.lordship.homes.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class HomeRepository {

    private static final Set<String> PATCHABLE_COLUMNS = Set.of(
            "name", "lot_id", "estimated_value", "estimated_value_on",
            "model_year", "make", "model", "bedroom_count", "bathroom_count",
            "width", "length", "dimensions_units", "sections", "condition",
            "appearance", "note", "parcel", "vin", "park_owned"
    );

    private final JdbcClient jdbc;

    public HomeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // the default for lordship: the save func defines the minimum data required to insert a row.
    // Section count is not known yet, so the name starts generic and the service upgrades it later.
    // Selecting from lot means a lot that is missing or deleted inserts nothing rather than
    // tripping the foreign key.
    public Optional<HomeRow> save(UUID lotId, UUID createdBy) {
        return jdbc.sql("""
            INSERT INTO mobile_home (
                lot_id, created_by, name
            )
            SELECT :lotId, :createdBy, 'Mobile home on lot ' || l.lot_number
              FROM lot l
             WHERE l.uuid = :lotId
               AND l.deleted_at IS NULL
            RETURNING *
            """)
                .param("lotId", lotId)
                .param("createdBy", createdBy)
                .query(HomeRow.class)
                .optional();
    }

    public Optional<HomeRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM mobile_home WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(HomeRow.class)
                .optional();
    }

    // a lot carries two homes while one is being pulled and the next set, so this is a list
    public List<HomeRow> findByLot(UUID lotId) {
        return jdbc.sql("""
            SELECT * FROM mobile_home
            WHERE lot_id = :lotId AND deleted_at IS NULL
            ORDER BY created_at
            """)
                .param("lotId", lotId)
                .query(HomeRow.class)
                .list();
    }

    public List<HomeRow> findByProperty(String propertyCode) {
        return jdbc.sql("""
            SELECT h.* FROM mobile_home h
            JOIN lot l ON l.uuid = h.lot_id
            JOIN property p ON p.uuid = l.property_id
            WHERE p.property_code = :propertyCode
              AND p.deleted_at IS NULL
              AND l.deleted_at IS NULL
              AND h.deleted_at IS NULL
            ORDER BY l.sort_order NULLS LAST, l.lot_number
            """)
                .param("propertyCode", propertyCode)
                .query(HomeRow.class)
                .list();
    }

    // vin is not unique: inherited paperwork duplicates, blanks, and multi-section serials in one field
    public List<HomeRow> findByVin(String vin) {
        return jdbc.sql("""
            SELECT * FROM mobile_home
            WHERE UPPER(vin) = UPPER(:vin) AND deleted_at IS NULL
            ORDER BY created_at
            """)
                .param("vin", vin)
                .query(HomeRow.class)
                .list();
    }

    // feeds the default name; a home may sit on no lot, hence Optional rather than a join on the row
    public Optional<String> findLotNumber(UUID lotId) {
        return jdbc.sql("SELECT lot_number FROM lot WHERE uuid = :lotId AND deleted_at IS NULL")
                .param("lotId", lotId)
                .query(String.class)
                .optional();
    }

    // The same as findById, and the row stays locked until the transaction ends.
    // A save reads its "before" this way, so two saves at once cannot both log the same before.
    public Optional<HomeRow> findByIdForUpdate(UUID uuid) {
        return jdbc.sql("SELECT * FROM mobile_home WHERE uuid = :uuid AND deleted_at IS NULL FOR UPDATE")
                .param("uuid", uuid)
                .query(HomeRow.class)
                .optional();
    }

    public Optional<HomeRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE mobile_home SET ");
        changes.forEach((col, val) -> sql.append(col).append(" = :").append(col).append(", "));
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(HomeRow.class)
                .optional();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("UPDATE mobile_home SET deleted_at = now() WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .update() > 0;
    }

    // A home whether it is deleted or not
    public Optional<HomeRow> findAnyById(UUID uuid) {
        return jdbc.sql("SELECT * FROM mobile_home WHERE uuid = :uuid")
                .param("uuid", uuid)
                .query(HomeRow.class)
                .optional();
    }

    // A lot's deleted homes, the most recently deleted first
    public List<HomeRow> findDeletedByLot(UUID lotId) {
        return jdbc.sql("""
            SELECT * FROM mobile_home
            WHERE lot_id = :lotId AND deleted_at IS NOT NULL
            ORDER BY deleted_at DESC
            """)
                .param("lotId", lotId)
                .query(HomeRow.class)
                .list();
    }

    // Brings a deleted home back. Empty when it was not deleted.
    public Optional<HomeRow> restore(UUID uuid) {
        return jdbc.sql("UPDATE mobile_home SET deleted_at = NULL WHERE uuid = :uuid AND deleted_at IS NOT NULL RETURNING *")
                .param("uuid", uuid)
                .query(HomeRow.class)
                .optional();
    }

    // The property of a lot, deleted or not. Empty for an unknown lot.
    public Optional<UUID> findPropertyIdOfLot(UUID lotId) {
        return jdbc.sql("SELECT property_id FROM lot WHERE uuid = :lotId")
                .param("lotId", lotId)
                .query(UUID.class)
                .optional();
    }

    // The property with this code, deleted or not
    public Optional<UUID> findPropertyIdByCode(String propertyCode) {
        return jdbc.sql("SELECT uuid FROM property WHERE property_code = :propertyCode")
                .param("propertyCode", propertyCode)
                .query(UUID.class)
                .optional();
    }

    public boolean isLotLive(UUID lotId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM lot WHERE uuid = :lotId AND deleted_at IS NULL)")
                .param("lotId", lotId)
                .query(Boolean.class)
                .single();
    }
}

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
public class StickBuiltRepository {

    private static final Set<String> PATCHABLE_COLUMNS = Set.of(
            "name", "lot_id", "year_built", "structure_type", "floor",
            "bedroom_count", "bathroom_count", "area", "area_units",
            "appearance", "park_owned", "note"
    );

    private final JdbcClient jdbc;

    public StickBuiltRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // the default for lordship: the save func defines the minimum data required to insert a row.
    // Structure type is not known yet, so the name starts generic and the service upgrades it later.
    // Selecting from lot means a lot that is missing or deleted inserts nothing rather than
    // tripping the foreign key.
    public Optional<StickBuiltRow> save(UUID lotId, UUID createdBy) {
        return jdbc.sql("""
            INSERT INTO stick_built (
                lot_id, created_by, name
            )
            SELECT :lotId, :createdBy, 'Building on lot ' || l.lot_number
              FROM lot l
             WHERE l.uuid = :lotId
               AND l.deleted_at IS NULL
            RETURNING *
            """)
                .param("lotId", lotId)
                .param("createdBy", createdBy)
                .query(StickBuiltRow.class)
                .optional();
    }

    public Optional<StickBuiltRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM stick_built WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(StickBuiltRow.class)
                .optional();
    }

    // Optional, not a list, unlike the mobile home side: one lot is one rentable unit
    // and uq_stick_built_lot enforces it. A house has no swap window to allow for.
    public Optional<StickBuiltRow> findByLot(UUID lotId) {
        return jdbc.sql("""
            SELECT * FROM stick_built
            WHERE lot_id = :lotId AND deleted_at IS NULL
            """)
                .param("lotId", lotId)
                .query(StickBuiltRow.class)
                .optional();
    }

    public List<StickBuiltRow> findByProperty(String propertyCode) {
        return jdbc.sql("""
            SELECT s.* FROM stick_built s
            JOIN lot l ON l.uuid = s.lot_id
            JOIN property p ON p.uuid = l.property_id
            WHERE p.property_code = :propertyCode
              AND p.deleted_at IS NULL
              AND l.deleted_at IS NULL
              AND s.deleted_at IS NULL
            ORDER BY l.sort_order NULLS LAST, l.lot_number
            """)
                .param("propertyCode", propertyCode)
                .query(StickBuiltRow.class)
                .list();
    }

    // feeds the generated name. Same query as HomeRepository's: the duplication is
    // cheaper than either repository depending on the other, and lot.internal is not
    // reachable from here.
    public Optional<String> findLotNumber(UUID lotId) {
        return jdbc.sql("SELECT lot_number FROM lot WHERE uuid = :lotId AND deleted_at IS NULL")
                .param("lotId", lotId)
                .query(String.class)
                .optional();
    }

    public Optional<StickBuiltRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE stick_built SET ");
        changes.forEach((col, val) -> sql.append(col).append(" = :").append(col).append(", "));
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(StickBuiltRow.class)
                .optional();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("UPDATE stick_built SET deleted_at = now() WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .update() > 0;
    }
}

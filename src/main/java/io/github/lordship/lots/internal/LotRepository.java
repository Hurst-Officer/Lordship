package io.github.lordship.lots.internal;

import io.github.lordship.lots.ShapeData;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class LotRepository {

    private static final Set<String> PATCHABLE_COLUMNS = Set.of(
            "lot_number", "title_link", "lot_address", "lot_parcel", "description", "notes",
            "is_rentable", "not_rentable_reason"
    );

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final LotRowMapper rowMapper;

    public LotRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.rowMapper = new LotRowMapper(objectMapper);
    }

    public LotRow save(UUID propertyId, String lotNumber) {
        return jdbc.sql("""
        INSERT INTO lot (property_id, lot_number, sort_order)
        VALUES (
            :propertyId,
            :lotNumber,
            (SELECT COALESCE(MAX(sort_order), 0) + 1
               FROM lot
              WHERE property_id = :propertyId)
        )
        RETURNING *
        """)
                .param("propertyId", propertyId)
                .param("lotNumber", lotNumber)
                .query(rowMapper)
                .single();
    }

    public Optional<LotRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM lot WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    // The same, and the row stays locked until the transaction ends.
    // A save reads its "before" this way, so two saves at once cannot both log the same before.
    public Optional<LotRow> findByIdForUpdate(UUID uuid) {
        return jdbc.sql("SELECT * FROM lot WHERE uuid = :uuid AND deleted_at IS NULL FOR UPDATE")
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    public Optional<LotRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE lot SET ");
        changes.forEach((col, val) -> sql.append(col).append(" = :").append(col).append(", "));
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(rowMapper)
                .optional();
    }

    public List<LotRow> findByProperty(String propertyCode) {
        return jdbc.sql("""
            SELECT l.* FROM lot l
            JOIN property p ON p.uuid = l.property_id
            WHERE p.property_code = :propertyCode
              AND p.deleted_at IS NULL
              AND l.deleted_at IS NULL
            ORDER BY sort_order NULLS LAST, lot_number
            """)
                .param("propertyCode", propertyCode)
                .query(rowMapper)
                .list();
    }

    public List<LotRow> findDuplicateNumbers(String propertyCode) {
        return jdbc.sql("""
            SELECT l.* FROM lot l
            JOIN property p ON p.uuid = l.property_id
            WHERE l.deleted_at IS NULL
              AND p.property_code = :propertyCode
              AND LOWER(lot_number) IN (
                  SELECT LOWER(lot_number) FROM lot
                  WHERE property_id = p.uuid AND deleted_at IS NULL
                  GROUP BY LOWER(lot_number) HAVING COUNT(*) > 1
              )
            ORDER BY LOWER(lot_number)
            """)
                .param("propertyCode", propertyCode)
                .query(rowMapper)
                .list();
    }

    // Where the lot is drawn on the park's plat, in drawing pixels
    public boolean saveShapeData(UUID uuid, ShapeData shape) {
        return jdbc.sql("UPDATE lot SET shape_data = :shape::jsonb WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("shape", objectMapper.writeValueAsString(shape))
                .param("uuid", uuid)
                .update() > 0;
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("UPDATE lot SET deleted_at = CURRENT_TIMESTAMP WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .update() > 0;
    }

    // A lot whether it is deleted or not
    public Optional<LotRow> findAnyById(UUID uuid) {
        return jdbc.sql("SELECT * FROM lot WHERE uuid = :uuid")
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    // A property's deleted lots, the most recently deleted first
    public List<LotRow> findDeletedByProperty(UUID propertyId) {
        return jdbc.sql("""
            SELECT * FROM lot
            WHERE property_id = :propertyId
              AND deleted_at IS NOT NULL
            ORDER BY deleted_at DESC, lot_number
            """)
                .param("propertyId", propertyId)
                .query(rowMapper)
                .list();
    }

    // Brings a deleted lot back. Empty when it was not deleted.
    public Optional<LotRow> restore(UUID uuid) {
        return jdbc.sql("UPDATE lot SET deleted_at = NULL WHERE uuid = :uuid AND deleted_at IS NOT NULL RETURNING *")
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    // sql: a tenancy is active while it has no end_date (see idx_tenancy_lot_active in V7)
    public boolean hasActiveTenancy(UUID lotId) {
        return jdbc.sql("""
            SELECT EXISTS (
                SELECT 1 FROM tenancy
                WHERE lot_id = :lotId
                  AND end_date IS NULL
                  AND deleted_at IS NULL
            )
            """)
                .param("lotId", lotId)
                .query(Boolean.class)
                .single();
    }

    public boolean isPropertyLive(UUID propertyId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM property WHERE uuid = :propertyId AND deleted_at IS NULL)")
                .param("propertyId", propertyId)
                .query(Boolean.class)
                .single();
    }
}
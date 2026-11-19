package io.github.lordship.tenants.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.*;

@Repository
public class TenantRepository {

    private final JdbcClient jdbc;

    private static final Set<String> ALLOWED_COLUMNS = Set.of(
            "start_date",
            "end_date"
    );

    public TenantRepository(JdbcClient jdbcClient) { this.jdbc = jdbcClient; }

    public TenantRow save(UUID tenancyId, UUID personId, LocalDate startDate) {
        return jdbc.sql("""
                INSERT INTO tenant (
                        tenancy_id, person_id, start_date
                    ) VALUES (
                        :tenancyId, :personId, :startDate
                    ) RETURNING *
                """)
                .param("tenancyId", tenancyId)
                .param("personId", personId)
                .param("startDate", startDate)
                .query(TenantRow.class)
                .single();
    }

    public Optional<TenantRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM tenant WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(TenantRow.class)
                .optional();
    }

    // Every stay on the tenancy, past ones included.
    public List<TenantRow> findByTenancy(UUID tenancyId) {
        return jdbc.sql("""
                SELECT * FROM tenant
                WHERE tenancy_id = :tenancyId AND deleted_at IS NULL
                ORDER BY start_date NULLS LAST, created_at
                """)
                .param("tenancyId", tenancyId)
                .query(TenantRow.class)
                .list();
    }

    // The household: who is on this tenancy now.
    public List<TenantRow> findActiveByTenancy(UUID tenancyId) {
        return jdbc.sql("""
                SELECT * FROM tenant
                WHERE tenancy_id = :tenancyId
                  AND end_date IS NULL AND deleted_at IS NULL
                ORDER BY start_date NULLS LAST, created_at
                """)
                .param("tenancyId", tenancyId)
                .query(TenantRow.class)
                .list();
    }

    // Backs uq_tenant_active_person: the same read the index enforces, so the
    // service can refuse a duplicate with a sentence instead of a constraint name.
    public Optional<TenantRow> findActiveByTenancyAndPerson(UUID tenancyId, UUID personId) {
        return jdbc.sql("""
                SELECT * FROM tenant
                WHERE tenancy_id = :tenancyId AND person_id = :personId
                  AND end_date IS NULL AND deleted_at IS NULL
                """)
                .param("tenancyId", tenancyId)
                .param("personId", personId)
                .query(TenantRow.class)
                .optional();
    }

    public List<TenantRow> findByPerson(UUID personId) {
        return jdbc.sql("""
                SELECT * FROM tenant
                WHERE person_id = :personId AND deleted_at IS NULL
                ORDER BY start_date NULLS LAST, created_at
                """)
                .param("personId", personId)
                .query(TenantRow.class)
                .list();
    }

    // A stay whether deleted or not, for the restore
    public Optional<TenantRow> findAnyById(UUID uuid) {
        return jdbc.sql("SELECT * FROM tenant WHERE uuid = :uuid")
                .param("uuid", uuid)
                .query(TenantRow.class)
                .optional();
    }

    // Brings a deleted stay back. Empty when the stay is unknown or not deleted.
    public Optional<TenantRow> restore(UUID uuid) {
        return jdbc.sql("UPDATE tenant SET deleted_at = NULL WHERE uuid = :uuid AND deleted_at IS NOT NULL RETURNING *")
                .param("uuid", uuid)
                .query(TenantRow.class)
                .optional();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("UPDATE tenant SET deleted_at = CURRENT_TIMESTAMP WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .update() > 0;
    }

    // start_date and end_date only. A move-out is end_date arriving here; there
    // is no separate close method, matching TenancyRepository's one door.
    public Optional<TenantRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!ALLOWED_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE tenant SET ");

        changes.forEach((col, val) -> sql.append(col).append(" = :").append(col).append(", "));

        sql.setLength(sql.length() - 2);

        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(TenantRow.class)
                .optional();
    }
}

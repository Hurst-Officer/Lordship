package io.github.lordship.tenants.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.*;

@Repository
public class OccupantRepository {

    private final JdbcClient jdbc;

    private static final Set<String> ALLOWED_COLUMNS = Set.of(
            "start_date",
            "end_date"
    );

    public OccupantRepository(JdbcClient jdbcClient) { this.jdbc = jdbcClient; }

    public OccupantRow save(UUID tenancyId, UUID personId, LocalDate startDate) {
        return jdbc.sql("""
                INSERT INTO occupant (
                        tenancy_id, person_id, start_date
                    ) VALUES (
                        :tenancyId, :personId, :startDate
                    ) RETURNING *
                """)
                .param("tenancyId", tenancyId)
                .param("personId", personId)
                .param("startDate", startDate)
                .query(OccupantRow.class)
                .single();
    }

    public Optional<OccupantRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM occupant WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(OccupantRow.class)
                .optional();
    }

    // Everyone who has ever lived here, past stays included.
    public List<OccupantRow> findByTenancy(UUID tenancyId) {
        return jdbc.sql("""
                SELECT * FROM occupant
                WHERE tenancy_id = :tenancyId AND deleted_at IS NULL
                ORDER BY start_date NULLS LAST, created_at
                """)
                .param("tenancyId", tenancyId)
                .query(OccupantRow.class)
                .list();
    }

    // Who is in the home now.
    public List<OccupantRow> findActiveByTenancy(UUID tenancyId) {
        return jdbc.sql("""
                SELECT * FROM occupant
                WHERE tenancy_id = :tenancyId
                  AND end_date IS NULL AND deleted_at IS NULL
                ORDER BY start_date NULLS LAST, created_at
                """)
                .param("tenancyId", tenancyId)
                .query(OccupantRow.class)
                .list();
    }

    // Backs uq_occupant_active_person: the same read the index enforces, so the
    // service can refuse a duplicate with a sentence instead of a constraint name.
    public Optional<OccupantRow> findActiveByTenancyAndPerson(UUID tenancyId, UUID personId) {
        return jdbc.sql("""
                SELECT * FROM occupant
                WHERE tenancy_id = :tenancyId AND person_id = :personId
                  AND end_date IS NULL AND deleted_at IS NULL
                """)
                .param("tenancyId", tenancyId)
                .param("personId", personId)
                .query(OccupantRow.class)
                .optional();
    }

    // Everywhere this person has lived -- the reason a kid who reappears in a
    // second tenancy three years later is the same person record.
    public List<OccupantRow> findByPerson(UUID personId) {
        return jdbc.sql("""
                SELECT * FROM occupant
                WHERE person_id = :personId AND deleted_at IS NULL
                ORDER BY start_date NULLS LAST, created_at
                """)
                .param("personId", personId)
                .query(OccupantRow.class)
                .list();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("UPDATE occupant SET deleted_at = CURRENT_TIMESTAMP WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .update() > 0;
    }

    // start_date and end_date only, matching TenantRepository's one door.
    public Optional<OccupantRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!ALLOWED_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE occupant SET ");

        changes.forEach((col, val) -> sql.append(col).append(" = :").append(col).append(", "));

        sql.setLength(sql.length() - 2);

        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(OccupantRow.class)
                .optional();
    }
}
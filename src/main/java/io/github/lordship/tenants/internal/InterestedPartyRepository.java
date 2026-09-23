package io.github.lordship.tenants.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.*;

@Repository
public class InterestedPartyRepository {

    private final JdbcClient jdbc;

    private static final Set<String> ALLOWED_COLUMNS = Set.of(
      "start_date", "end_date", "accept_payments", "notes", "notification_reason,"
    );

    public InterestedPartyRepository(JdbcClient jdbc) { this.jdbc = jdbc; }


    public InterestedPartyRow save(UUID tenancyId, UUID personId, LocalDate startDate) {
        return jdbc.sql("""
                INSERT INTO tenancy_interested_party
                    (tenancy_id, person_id, start_date)
                    VALUES
                    (:tenancyId, :personId, :startDate)
                    RETURNING *
                """)
                .param("tenancyId", tenancyId)
                .param("personId", personId)
                .param("startDate", startDate)
                .query(InterestedPartyRow.class)
                .single();
    }

    public Optional<InterestedPartyRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM tenancy_interested_party WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(InterestedPartyRow.class)
                .optional();
    }

    // Every stay on the tenancy, past ones included.
    public List<InterestedPartyRow> findByTenancy(UUID tenancyId) {
        return jdbc.sql("""
                SELECT * FROM tenancy_interested_party
                WHERE tenancy_id = :tenancyId AND deleted_at IS NULL
                ORDER BY start_date NULLS LAST, created_at
                """)
                .param("tenancyId", tenancyId)
                .query(InterestedPartyRow.class)
                .list();
    }

    // The household: who is interested in this tenancy now.
    public List<InterestedPartyRow> findActiveByTenancy(UUID tenancyId) {
        return jdbc.sql("""
                SELECT * FROM tenancy_interested_party
                WHERE tenancy_id = :tenancyId
                  AND end_date IS NULL AND deleted_at IS NULL
                ORDER BY start_date NULLS LAST, created_at
                """)
                .param("tenancyId", tenancyId)
                .query(InterestedPartyRow.class)
                .list();
    }

    public Optional<InterestedPartyRow> findActiveByTenancyAndPerson(UUID tenancyId, UUID personId) {
        return jdbc.sql("""
                SELECT * FROM tenancy_interested_party
                WHERE tenancy_id = :tenancyId AND person_id = :personId
                  AND end_date IS NULL AND deleted_at IS NULL
                """)
                .param("tenancyId", tenancyId)
                .param("personId", personId)
                .query(InterestedPartyRow.class)
                .optional();
    }

    public List<InterestedPartyRow> findByPerson(UUID personId) {
        return jdbc.sql("""
                SELECT * FROM tenancy_interested_party
                WHERE person_id = :personId AND deleted_at IS NULL
                ORDER BY start_date NULLS LAST, created_at
                """)
                .param("personId", personId)
                .query(InterestedPartyRow.class)
                .list();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("UPDATE tenancy_interested_party SET deleted_at = CURRENT_TIMESTAMP WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .update() > 0;
    }

    public Optional<InterestedPartyRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!ALLOWED_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE tenancy_interested_party SET ");

        changes.forEach((col, val) -> sql.append(col).append(" = :").append(col).append(", "));

        sql.setLength(sql.length() - 2);

        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(InterestedPartyRow.class)
                .optional();
    }
}
package io.github.lordship.homes.internal;

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
public class SecuredPartyRepository {

    private static final Set<String> PATCHABLE_COLUMNS = Set.of(
            "start_date", "end_date", "accept_payments"
    );

    private final JdbcClient jdbc;

    public SecuredPartyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Selecting from mobile_home means a home that is missing or deleted inserts
    // nothing rather than tripping the foreign key.
    public Optional<SecuredPartyRow> save(UUID mobileHomeId, UUID personId, LocalDate startDate) {
        return jdbc.sql("""
            INSERT INTO mobile_home_secured_party (
                mobile_home_id, person_id, start_date
            )
            SELECT :mobileHomeId, :personId, :startDate
              FROM mobile_home m
             WHERE m.uuid = :mobileHomeId
               AND m.deleted_at IS NULL
            RETURNING *
            """)
                .param("mobileHomeId", mobileHomeId)
                .param("personId", personId)
                .param("startDate", startDate)
                .query(SecuredPartyRow.class)
                .optional();
    }

    public Optional<SecuredPartyRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM mobile_home_secured_party WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(SecuredPartyRow.class)
                .optional();
    }

    // Every claim this home has carried, past ones included.
    public List<SecuredPartyRow> findByHome(UUID mobileHomeId) {
        return jdbc.sql("""
            SELECT * FROM mobile_home_secured_party
            WHERE mobile_home_id = :mobileHomeId AND deleted_at IS NULL
            ORDER BY start_date NULLS LAST, created_at
            """)
                .param("mobileHomeId", mobileHomeId)
                .query(SecuredPartyRow.class)
                .list();
    }

    // Who currently has a claim on this home.
    public List<SecuredPartyRow> findActiveByHome(UUID mobileHomeId) {
        return jdbc.sql("""
            SELECT * FROM mobile_home_secured_party
            WHERE mobile_home_id = :mobileHomeId
              AND end_date IS NULL AND deleted_at IS NULL
            ORDER BY start_date NULLS LAST, created_at
            """)
                .param("mobileHomeId", mobileHomeId)
                .query(SecuredPartyRow.class)
                .list();
    }

    public Optional<SecuredPartyRow> findActiveByHomeAndPerson(UUID mobileHomeId, UUID personId) {
        return jdbc.sql("""
            SELECT * FROM mobile_home_secured_party
            WHERE mobile_home_id = :mobileHomeId AND person_id = :personId
              AND end_date IS NULL AND deleted_at IS NULL
            """)
                .param("mobileHomeId", mobileHomeId)
                .param("personId", personId)
                .query(SecuredPartyRow.class)
                .optional();
    }

    public List<SecuredPartyRow> findByPerson(UUID personId) {
        return jdbc.sql("""
            SELECT * FROM mobile_home_secured_party
            WHERE person_id = :personId AND deleted_at IS NULL
            ORDER BY start_date NULLS LAST, created_at
            """)
                .param("personId", personId)
                .query(SecuredPartyRow.class)
                .list();
    }

    public Optional<SecuredPartyRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        for (String col : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE mobile_home_secured_party SET ");
        changes.forEach((col, val) -> sql.append(col).append(" = :").append(col).append(", "));
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(SecuredPartyRow.class)
                .optional();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("UPDATE mobile_home_secured_party SET deleted_at = now() WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .update() > 0;
    }
}

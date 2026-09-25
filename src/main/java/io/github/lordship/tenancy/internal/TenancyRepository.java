package io.github.lordship.tenancy.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.*;

@Repository
public class TenancyRepository {

    private final JdbcClient jdbc;

    private static final Set<String> ALLOWED_COLUMNS = Set.of(
            "lot_id",
            "start_date",
            "end_date",
            "no_personal_checks",
            "no_partial_payments",
            "accept_payments",
            "exempt_from_late_fees",
            "notes"
    );

    public TenancyRepository(JdbcClient jdbcClient) {
        this.jdbc = jdbcClient;
    }

    // A tenancy with no start date. Test fixtures use this.
    public TenancyRow save(UUID lotUuid) {
        return save(lotUuid, null);
    }

    public TenancyRow save(UUID lotUuid, LocalDate startDate) {
        return jdbc.sql("""
                        INSERT INTO tenancy (
                                lot_id, start_date
                            ) VALUES (
                                :lotId, :startDate
                            ) RETURNING *
                        """)
                .param("lotId", lotUuid)
                .param("startDate", startDate)
                .query(TenancyRow.class)
                .single();
    }

    public Optional<TenancyRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM tenancy WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(TenancyRow.class)
                .optional();
    }

    // Every tenancy on the lot, ended ones included. Used by the two-per-month rule.
    public List<TenancyRow> findByLot(UUID lotId) {
        return jdbc.sql("SELECT * FROM tenancy WHERE lot_id = :lotId AND deleted_at IS NULL")
                .param("lotId", lotId)
                .query(TenancyRow.class)
                .list();
    }

    // Some lots may have two tenancies at a time
    public List<TenancyRow> findActiveByLot(UUID lotId) {
        return jdbc.sql("""
                        SELECT * from tenancy WHERE lot_id = :lotId
                        AND end_date IS NULL AND deleted_at IS NULL
                        """)
                .param("lotId", lotId)
                .query(TenancyRow.class)
                .list();
    }

    // Determines when a tenancyId closes
    public TenancyRow close(UUID uuid, LocalDate endDate) {
        return jdbc.sql("""
                        UPDATE tenancy
                        SET end_date = :endDate
                        WHERE uuid = :uuid AND deleted_at IS NULL
                        RETURNING *
                        """)
                .param("uuid", uuid)
                .param("endDate", endDate)
                .query(TenancyRow.class)
                .single();
    }

    // Stores deleted tenancies instead of removing them
    public boolean softDelete(UUID uuid) {
        return jdbc.sql("UPDATE tenancy SET deleted_at = CURRENT_TIMESTAMP WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .update() > 0;
    }

    public Optional<TenancyRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) return findById(uuid);

        // Only allow specific columns to be patched
        for (String col : changes.keySet()) {
            if (!ALLOWED_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Invalid column: " + col);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE tenancy SET ");

        changes.forEach((col, val) -> sql.append(col).append(" = :").append(col).append(", "));

        // Remove trailing comma
        sql.setLength(sql.length() - 2);

        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(TenancyRow.class)
                .optional();
    }
}
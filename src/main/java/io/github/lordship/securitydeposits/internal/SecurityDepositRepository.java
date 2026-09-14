package io.github.lordship.securitydeposits.internal;

import io.github.lordship.securitydeposits.HeldDeposit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class SecurityDepositRepository {

    // tenancy, source, created_by and created_at are set once at creation.
    private static final Set<String> PATCHABLE_COLUMNS = Set.of(
            "instrument", "amount", "collected_on", "settled_on", "description", "note");

    // Both held queries answer from the same shape; they differ only in
    // whether the tenant has already gone, and in what "first" means.
    private static final String HELD_SELECT = """
            SELECT d.uuid,
                   d.tenancy,
                   l.uuid       AS lot,
                   l.lot_number AS lot_number,
                   d.amount,
                   d.collected_on,
                   t.end_date   AS tenancy_ended_on,
                   (CURRENT_DATE - t.end_date) AS days_since_tenancy_ended
              FROM security_deposit d
              JOIN tenancy t ON t.uuid = d.tenancy AND t.deleted_at IS NULL
              JOIN lot l     ON l.uuid = t.lot_id  AND l.deleted_at IS NULL
             WHERE l.property_id = :propertyId
               AND d.settled_on IS NULL
               AND d.deleted_at IS NULL
            """;

    private final JdbcClient jdbc;
    private final SecurityDepositRowMapper rowMapper;

    public SecurityDepositRepository(JdbcClient jdbc, SecurityDepositRowMapper rowMapper) {
        this.jdbc = jdbc;
        this.rowMapper = rowMapper;
    }

    public SecurityDepositRow save(SecurityDepositRow row) {
        return jdbc.sql("""
                INSERT INTO security_deposit (tenancy, amount, source, collected_on, created_by)
                VALUES (:tenancy, :amount, :source, :collectedOn, :createdBy)
                RETURNING *
                """)
                .param("tenancy", row.tenancy())
                .param("amount", row.amount())
                .param("source", nameOf(row.source()))
                .param("collectedOn", row.collectedOn())
                .param("createdBy", row.createdBy())
                .query(rowMapper)
                .single();
    }

    public Optional<SecurityDepositRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM security_deposit WHERE uuid = :uuid AND deleted_at IS NULL")
                .param("uuid", uuid)
                .query(rowMapper)
                .optional();
    }

    // The whole deposit history for one tenancy, settled rows included.
    public List<SecurityDepositRow> findByTenancy(UUID tenancy) {
        return jdbc.sql("""
                SELECT * FROM security_deposit
                WHERE tenancy = :tenancy AND deleted_at IS NULL
                ORDER BY collected_on DESC NULLS LAST, created_at DESC
                """)
                .param("tenancy", tenancy)
                .query(rowMapper)
                .list();
    }

    // What this park is still holding. The rent roll figure.
    public List<HeldDeposit> findHeldByProperty(UUID propertyId) {
        return jdbc.sql(HELD_SELECT + " ORDER BY l.sort_order, d.collected_on")
                .param("propertyId", propertyId)
                .query(HeldDeposit.class)
                .list();
    }

    // The work queue: still held, but the tenant has gone. Oldest first,
    // because that is the one closest to being a problem.
    public List<HeldDeposit> findAgingByProperty(UUID propertyId) {
        return jdbc.sql(HELD_SELECT + " AND t.end_date IS NOT NULL ORDER BY t.end_date")
                .param("propertyId", propertyId)
                .query(HeldDeposit.class)
                .list();
    }

    // What this tenancy has actually handed over and not had back. Compared
    // against the charge term's figure, this is the shortfall.
    public BigDecimal sumHeldByTenancy(UUID tenancy) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(amount), 0)
                  FROM security_deposit
                 WHERE tenancy = :tenancy
                   AND settled_on IS NULL
                   AND deleted_at IS NULL
                """)
                .param("tenancy", tenancy)
                .query(BigDecimal.class)
                .single();
    }

    public Optional<SecurityDepositRow> patch(UUID uuid, Map<String, Object> changes) {
        if (changes.isEmpty()) {
            return findById(uuid);
        }

        for (String column : changes.keySet()) {
            if (!PATCHABLE_COLUMNS.contains(column)) {
                throw new IllegalArgumentException("Invalid column: " + column);
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE security_deposit SET ");
        changes.forEach((column, value) -> sql.append(column).append(" = :").append(column).append(", "));
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");

        Map<String, Object> params = new HashMap<>(changes);
        params.put("uuid", uuid);

        return jdbc.sql(sql.toString())
                .params(params)
                .query(rowMapper)
                .optional();
    }

    public boolean softDelete(UUID uuid) {
        return jdbc.sql("""
                UPDATE security_deposit SET deleted_at = now()
                WHERE uuid = :uuid AND deleted_at IS NULL
                """)
                .param("uuid", uuid)
                .update() > 0;
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
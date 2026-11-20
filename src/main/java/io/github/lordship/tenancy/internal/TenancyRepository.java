package io.github.lordship.tenancy.internal;

import io.github.lordship.shared.TenancyStanding;
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

    // A tenancy whether deleted or not, for the restore
    public Optional<TenancyRow> findAnyById(UUID uuid) {
        return jdbc.sql("SELECT * FROM tenancy WHERE uuid = :uuid")
                .param("uuid", uuid)
                .query(TenancyRow.class)
                .optional();
    }

    // Brings a deleted tenancy back. Empty when the tenancy is unknown or not deleted.
    public Optional<TenancyRow> restore(UUID uuid) {
        return jdbc.sql("UPDATE tenancy SET deleted_at = NULL WHERE uuid = :uuid AND deleted_at IS NOT NULL RETURNING *")
                .param("uuid", uuid)
                .query(TenancyRow.class)
                .optional();
    }

    /**
     * How many other live tenancies hold the lot on a day (TenancyStanding.HOLDS_ON_DAY_SQL). A tenancy holds
     * the lot until the tenancy's end date (the end date itself is the day the next tenancy may start), and while
     * the tenancy has no end date.
     * self is the tenancy being checked, left out of the count; null when creating. A null day means today.
     */
    public int countHolding(UUID lotId, UUID self, LocalDate day) {
        return jdbc.sql("""
                        SELECT count(*)::int FROM tenancy t
                        WHERE t.lot_id = :lotId
                          AND t.deleted_at IS NULL
                          AND (CAST(:self AS uuid) IS NULL OR t.uuid <> CAST(:self AS uuid))
                          AND %s
                        """.formatted(TenancyStanding.HOLDS_ON_DAY_SQL))
                .param("lotId", lotId)
                .param("self", self)
                .param("day", day)
                .query(Integer.class)
                .single();
    }

    /**
     * Whether a tenancy that holds the lot on a day (see countHolding) has a charge term of one of these kinds.
     * kinds are agreement_type names ("LAND"). Cancelled and deleted terms do not count. tenancy_charge_term is
     * the tenancyterms module's table, read here by name: that module depends on this one.
     * self is a tenancy left out of the search (the tenancy a lease is being started for); null leaves none out.
     */
    public boolean holdsKind(UUID lotId, UUID self, List<String> kinds, LocalDate day) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM tenancy t
                            JOIN tenancy_charge_term c ON c.tenancy = t.uuid
                            WHERE t.lot_id = :lotId
                              AND t.deleted_at IS NULL
                              AND (CAST(:self AS uuid) IS NULL OR t.uuid <> CAST(:self AS uuid))
                              AND %s
                              AND c.deleted_at IS NULL
                              AND c.status <> 'CANCELLED'
                              AND c.agreement_type::text IN (:kinds)
                        )
                        """.formatted(TenancyStanding.HOLDS_ON_DAY_SQL))
                .param("lotId", lotId)
                .param("self", self)
                .param("kinds", kinds)
                .param("day", day)
                .query(Boolean.class)
                .single();
    }

    // Whether the tenancy's account has any charge, payment or adjustment on the ledger. account and transaction
    // are other modules' tables, read here by name, so that this one question adds nothing to the accounts and
    // transactions packages. The query only reads.
    public boolean hasLedgerEntries(UUID tenancyId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM "transaction" x
                            JOIN account a ON a.uuid = x.account_id
                            WHERE a.tenancy_id = :tenancyId
                              AND x.deleted_at IS NULL
                        )
                        """)
                .param("tenancyId", tenancyId)
                .query(Boolean.class)
                .single();
    }

    // Whether a document of the tenancy was generated and not abandoned: paper with a serial exists, and may be
    // in a tenant's hands. instrument is the instruments module's table, read here by name: that module depends
    // on this one. The query only reads.
    public boolean hasPaperOut(UUID tenancyId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM instrument i
                            WHERE i.tenancy = :tenancyId
                              AND i.status IN ('GENERATED', 'SENT', 'SERVED', 'APPROVED')
                        )
                        """)
                .param("tenancyId", tenancyId)
                .query(Boolean.class)
                .single();
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
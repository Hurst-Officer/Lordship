package io.github.lordship.tenancy.internal;

import io.github.lordship.tenancy.MoveIn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Move-In facts of a tenancy (the tenancy table's move_in_ columns), and the joins Glade's Move-In lists are
 * made from. Writes go through MoveIns, which records them in the audit log.
 *
 * <p>The lists read the lot, property, tenant, person, instrument, tenancy_charge_term and move_in_file tables by
 * name on purpose, the way TenancyRollRepository does: a move-in's row cannot be shown without who is moving in and
 * where the lease and the lease's signed scan stand, and the modules that own those tables already depend on this one.
 * The office's list (move_in_check) is this module's own.
 */
@Repository
public class MoveInRepository {

    // The columns a PATCH may change, by the name the request uses
    private static final Map<String, String> PATCHABLE = Map.of(
            "moveInSigningOn", "move_in_signing_on",
            "moveInSignedOn", "move_in_signed_on",
            "moveInCopyGiven", "move_in_copy_given");

    // sql: the Move-In facts of one tenancy, under the names MoveInRow takes
    private static final String FACTS = """
            uuid AS tenancy_id, move_in_started_at, move_in_signing_on, move_in_signed_on, move_in_copy_given,
            move_in_done_at, move_in_done_by""";

    // sql: one move-in as a row of a list. %s is the test that picks the tenancies, written against t (tenancy),
    // l (lot) and p (property). The tenants' names are joined with the unit separator (chr 31), which no name
    // holds. The lease is the newest lease of the tenancy that was not abandoned. The signed scan is the newest
    // live SIGNED_LEASE upload of that lease, and the page check is the PAGES_IN_ORDER item of the office's list.
    private static final String LINES = """
            SELECT t.uuid AS tenancy_id, t.lot_id, l.property_id, p.property_code, l.lot_number, t.start_date,
                   (SELECT string_agg(pe.name_full, chr(31) ORDER BY tn.start_date NULLS LAST, tn.created_at)
                    FROM tenant tn
                    JOIN person pe ON pe.uuid = tn.person_id
                    WHERE tn.tenancy_id = t.uuid AND tn.deleted_at IS NULL AND tn.end_date IS NULL) AS tenant_names,
                   (SELECT count(*)
                    FROM tenant tn
                    WHERE tn.tenancy_id = t.uuid AND tn.deleted_at IS NULL AND tn.end_date IS NULL)::int AS tenant_count,
                   lease.uuid AS lease_id, lease.status AS lease_status, lease.serial AS lease_serial,
                   lease.generated_at AS lease_generated_at, lease.agreement_type AS agreement_type,
                   EXISTS (SELECT 1
                           FROM tenancy_charge_term c
                           WHERE c.source_uuid = lease.uuid
                             AND c.deleted_at IS NULL
                             AND c.status = 'ACTIVE') AS rent_accepted,
                   t.move_in_started_at, t.move_in_signing_on, t.move_in_signed_on, t.move_in_copy_given,
                   t.move_in_done_at, t.deleted_at,
                   (SELECT max(f.created_at)
                    FROM move_in_file f
                    WHERE f.tenancy_id = t.uuid
                      AND f.instrument = lease.uuid
                      AND f.kind = 'SIGNED_LEASE'
                      AND f.deleted_at IS NULL) AS scan_uploaded_at,
                   EXISTS (SELECT 1
                           FROM move_in_check c
                           WHERE c.tenancy_id = t.uuid
                             AND c.item = 'PAGES_IN_ORDER'
                             AND c.deleted_at IS NULL
                             AND c.state IN ('PASSED', 'WAIVED', 'NOT_NEEDED')) AS pages_settled,
                   (SELECT count(*)
                    FROM move_in_check c
                    WHERE c.tenancy_id = t.uuid
                      AND c.deleted_at IS NULL
                      AND c.state = 'FAILED')::int AS checks_failed,
                   (SELECT count(*)
                    FROM move_in_check c
                    WHERE c.tenancy_id = t.uuid
                      AND c.item = 'REQUEST'
                      AND c.deleted_at IS NULL
                      AND c.answer IS NULL
                      AND c.state NOT IN ('PASSED', 'WAIVED', 'NOT_NEEDED'))::int AS requests_open
            FROM tenancy t
            JOIN lot l ON l.uuid = t.lot_id AND l.deleted_at IS NULL
            JOIN property p ON p.uuid = l.property_id AND p.deleted_at IS NULL
            LEFT JOIN LATERAL (
                SELECT i.uuid, i.status, i.serial, i.generated_at, i.agreement_type::text AS agreement_type
                FROM instrument i
                WHERE i.tenancy = t.uuid
                  AND i.type = 'LEASE'
                  AND i.status <> 'ABANDONED'
                ORDER BY i.created_at DESC
                LIMIT 1
            ) lease ON TRUE
            WHERE t.move_in_started_at IS NOT NULL
              AND %s
            ORDER BY t.move_in_started_at""";

    private final JdbcClient jdbc;

    public MoveInRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // The Move-In facts of one tenancy, the names the audit log keeps them under. moveInStartedAt is null for a
    // tenancy that did not come through Move-In.
    public record MoveInRow(UUID tenancyId, OffsetDateTime moveInStartedAt, LocalDate moveInSigningOn,
                            LocalDate moveInSignedOn, boolean moveInCopyGiven, OffsetDateTime moveInDoneAt,
                            UUID moveInDoneBy) {
        public MoveIn toMoveIn() {
            return new MoveIn(tenancyId, moveInStartedAt, moveInSigningOn, moveInSignedOn, moveInCopyGiven,
                    moveInDoneAt, moveInDoneBy);
        }
    }

    // One move-in as the lists read the move-in. tenantNames is the names joined with chr(31), or null.
    public record LineRow(UUID tenancyId, UUID lotId, UUID propertyId, String propertyCode, String lotNumber,
                          LocalDate startDate, String tenantNames, int tenantCount, UUID leaseId, String leaseStatus,
                          String leaseSerial, OffsetDateTime leaseGeneratedAt, String agreementType,
                          boolean rentAccepted, OffsetDateTime moveInStartedAt, LocalDate moveInSigningOn,
                          LocalDate moveInSignedOn, boolean moveInCopyGiven, OffsetDateTime moveInDoneAt,
                          OffsetDateTime deletedAt, OffsetDateTime scanUploadedAt, boolean pagesSettled,
                          int checksFailed, int requestsOpen) { }

    // The request's names of the columns a PATCH may change
    public static Set<String> patchable() {
        return PATCHABLE.keySet();
    }

    // The Move-In facts of a tenancy that is not deleted, whether or not the tenancy came through Move-In
    public Optional<MoveInRow> findFacts(UUID tenancyId) {
        return jdbc.sql("SELECT " + FACTS + " FROM tenancy WHERE uuid = :tenancyId AND deleted_at IS NULL")
                .param("tenancyId", tenancyId)
                .query(MoveInRow.class)
                .optional();
    }

    /**
     * Writes some Move-In facts of a move-in that is not finished. changes is keyed by the request's names (see
     * patchable); the caller has checked the values. Empty when the tenancy is gone, is not a move-in, or was
     * finished meanwhile.
     */
    public Optional<MoveInRow> update(UUID tenancyId, Map<String, Object> changes) {
        String sets = changes.keySet().stream()
                .map(name -> PATCHABLE.get(name) + " = :" + name)
                .collect(Collectors.joining(", "));
        JdbcClient.StatementSpec statement = jdbc.sql("""
                UPDATE tenancy SET %s
                WHERE uuid = :tenancyId
                  AND deleted_at IS NULL
                  AND move_in_started_at IS NOT NULL
                  AND move_in_done_at IS NULL
                RETURNING %s""".formatted(sets, FACTS))
                .param("tenancyId", tenancyId);
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            statement = statement.param(change.getKey(), change.getValue());
        }
        return statement.query(MoveInRow.class).optional();
    }

    // Marks a tenancy as opened through Move-In. Empty when the tenancy was already marked (or is gone).
    public Optional<MoveInRow> markStarted(UUID tenancyId) {
        return jdbc.sql("""
                UPDATE tenancy SET move_in_started_at = now()
                WHERE uuid = :tenancyId
                  AND move_in_started_at IS NULL
                RETURNING %s""".formatted(FACTS))
                .param("tenancyId", tenancyId)
                .query(MoveInRow.class)
                .optional();
    }

    // Finishes a move-in. Empty when the tenancy is gone, is not a move-in, or was already finished.
    public Optional<MoveInRow> markDone(UUID tenancyId, UUID agentId) {
        return jdbc.sql("""
                UPDATE tenancy SET move_in_done_at = now(), move_in_done_by = :agentId
                WHERE uuid = :tenancyId
                  AND deleted_at IS NULL
                  AND move_in_started_at IS NOT NULL
                  AND move_in_done_at IS NULL
                RETURNING %s""".formatted(FACTS))
                .param("tenancyId", tenancyId)
                .param("agentId", agentId)
                .query(MoveInRow.class)
                .optional();
    }

    // Every move-in in process (not finished) on a live tenancy, lot and property, the oldest first
    public List<LineRow> findInProcess() {
        return jdbc.sql(LINES.formatted("t.deleted_at IS NULL AND t.move_in_done_at IS NULL"))
                .query(LineRow.class)
                .list();
    }

    // A property's move-ins: those in process, and those finished in the last 90 days, the oldest first
    public List<LineRow> findByProperty(UUID propertyId) {
        return jdbc.sql(LINES.formatted("""
                t.deleted_at IS NULL
                  AND l.property_id = :propertyId
                  AND (t.move_in_done_at IS NULL OR t.move_in_done_at >= now() - interval '90 days')"""))
                .param("propertyId", propertyId)
                .query(LineRow.class)
                .list();
    }

    // Every move-in a lot has had, the tenancy deleted or not, finished or not, the oldest first
    public List<LineRow> findByLot(UUID lotId) {
        return jdbc.sql(LINES.formatted("t.lot_id = :lotId"))
                .param("lotId", lotId)
                .query(LineRow.class)
                .list();
    }

    // One tenancy's move-in, the tenancy deleted or not. Empty when the tenancy did not come through Move-In.
    public Optional<LineRow> findLine(UUID tenancyId) {
        return jdbc.sql(LINES.formatted("t.uuid = :tenancyId"))
                .param("tenancyId", tenancyId)
                .query(LineRow.class)
                .optional();
    }
}

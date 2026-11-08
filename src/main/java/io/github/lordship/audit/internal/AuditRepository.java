package io.github.lordship.audit.internal;


import io.github.lordship.audit.AuditLog;
import io.github.lordship.shared.EncryptionService;
import io.github.lordship.shared.PageRequest;
import io.github.lordship.shared.PageResult;
import io.github.lordship.shared.UserType;
import io.github.lordship.audit.OperationType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;


@Repository
public class AuditRepository {

    // allow-list of columns that may be used in ORDER BY; sortBy is request-controlled
    // and can't be bound as a normal param, so it must be validated before concatenation
    private static final Set<String> ALLOWED_SORT_COLUMNS = Set.of(
            "changed_at", "table_name", "record_id", "operation"
    );

    private final JdbcClient jdbc;
    private final EncryptionService encryptionService;

    public AuditRepository(JdbcClient jdbc,
                           EncryptionService encryptionService) {
        this.jdbc = jdbc;
        this.encryptionService = encryptionService;
    }

    private static final RowMapper<AuditLogRow> ROW_MAPPER = (rs, _) -> new AuditLogRow(
            rs.getObject("uuid", UUID.class),
            rs.getObject("correlation_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            UserType.valueOf(rs.getString("user_type")),
            rs.getString("ip_address"),
            rs.getString("table_name"),
            rs.getString("record_id"),
            OperationType.valueOf(rs.getString("operation")),
            rs.getString("value_before"),
            rs.getString("value_after"),
            rs.getObject("changed_at", OffsetDateTime.class)
    );

    public AuditLogRow save(AuditLogRow auditLogRow) {
        return jdbc.sql("""
                    insert into audit_log (
                        correlation_id, user_id, user_type, ip_address,
                        table_name, record_id, operation, value_before, value_after
                    )
                    values (
                        :correlationId, :userId, :userType::user_type, :ipAddress,
                        :tableName, :recordId, :operation::operation_type, :valueBefore, :valueAfter
                    )
                    RETURNING *
                """)
                .param("correlationId", auditLogRow.correlationId())
                .param("userId", auditLogRow.userId())
                .param("userType", auditLogRow.userType().name())
                .param("ipAddress", auditLogRow.ipAddress())
                .param("tableName", auditLogRow.tableName())
                .param("recordId", auditLogRow.recordId())
                .param("operation", auditLogRow.operation().name())
                .param("valueBefore", auditLogRow.valueBefore())
                .param("valueAfter", auditLogRow.valueAfter())
                .query(ROW_MAPPER)
                .single();
    }

    public PageResult<AuditLog> findAllAgentAuditLogs(UUID agentId, PageRequest pageRequest) {
        if (!ALLOWED_SORT_COLUMNS.contains(pageRequest.sortBy())) {
            throw new IllegalArgumentException("Invalid sortBy column: " + pageRequest.sortBy());
        }
        String direction = pageRequest.ascending() ? "ASC" : "DESC";

        long total = jdbc.sql("""
                    SELECT count(*) FROM audit_log
                    WHERE user_id = :agentId
                    AND user_type = 'AGENT'
                    """)
                .param("agentId", agentId)
                .query(Long.class)
                .single();

        // Rows written in one transaction share a time, so the id (a uuidv7, in time order) breaks ties.
        // Without it, rows with the same time come back in any order, and paging could skip or repeat a row.
        List<AuditLogRow> content = jdbc.sql("""
                    SELECT * FROM audit_log
                    WHERE user_id = :agentId
                    AND user_type = 'AGENT'
                    ORDER BY %s %s, uuid %s
                    LIMIT :limit OFFSET :offset
                    """.formatted(pageRequest.sortBy(), direction, direction))
                .param("agentId", agentId)
                .param("limit", pageRequest.pageSize())
                .param("offset", pageRequest.offset())
                .query(ROW_MAPPER)
                .list();

        List<AuditLog> auditLogs = content.stream()
                .map(row -> row.toAuditLog(encryptionService))
                .toList();

        return PageResult.of(auditLogs, pageRequest, total);
    }

    // ---- an agent's own activity (Glade: My information) ---------------------------------

    // What "My activity" may sort by, to the column it sorts on. Anything else sorts by time.
    private static final Map<String, String> OWN_SAVE_SORTS = Map.of(
            "when", "changed_at",
            "kind", "operation",
            "record", "lower(record_name)");

    /**
     * The agent's saves, one row per request and table and operation, so a bulk change of 30 lots is one row.
     * Each is named from its record where the kind is known (property, lot, home, person, agent, role);
     * a lot or a home also gives its property. Deleted records keep their names.
     * Ties (and the time sort) fall back to newest first. limit is one more than a page, to know whether more remain.
     */
    public List<HistoryRows.SaveRow> findOwnSaves(UUID agentId, String sort, boolean ascending, int limit, int offset) {
        String column = OWN_SAVE_SORTS.getOrDefault(sort, "changed_at");
        String direction = ascending ? "ASC" : "DESC";
        return jdbc.sql("""
                    WITH saves AS (
                        SELECT COALESCE(a.correlation_id, a.uuid) AS save_id,
                               a.table_name,
                               a.operation::text AS operation,
                               max(a.changed_at) AS changed_at,
                               max(a.uuid::text) AS last_id,
                               count(DISTINCT a.uuid)::int AS records,
                               (array_agg(a.record_id ORDER BY a.uuid))[1] AS record_id,
                               string_agg(DISTINCT k.key, ',' ORDER BY k.key) AS fields
                          FROM audit_log a
                          LEFT JOIN LATERAL jsonb_object_keys(
                                CASE WHEN a.operation = 'UPDATE' AND jsonb_typeof(a.value_after::jsonb) = 'object'
                                     THEN a.value_after::jsonb ELSE '{}'::jsonb END) AS k(key) ON true
                         WHERE a.user_id = :agentId
                           AND a.user_type = 'AGENT'
                         GROUP BY save_id, a.table_name, a.operation
                    ), named AS (
                        SELECT s.*,
                               COALESCE(pr.property_name, l.lot_number, h.name, h.vin, pe.name_full, ap.name_full, r.role_name)
                                   AS record_name,
                               COALESCE(pr.uuid, lp.uuid) AS property_id,
                               COALESCE(pr.property_name, lp.property_name) AS property_name,
                               COALESCE(l.uuid, hl.uuid) AS lot_id
                          FROM saves s
                          LEFT JOIN property pr ON s.table_name = 'property' AND pr.uuid::text = s.record_id
                          LEFT JOIN lot l ON s.table_name = 'lot' AND l.uuid::text = s.record_id
                          LEFT JOIN mobile_home h ON s.table_name = 'mobile_home' AND h.uuid::text = s.record_id
                          LEFT JOIN lot hl ON hl.uuid = h.lot_id
                          LEFT JOIN property lp ON lp.uuid = COALESCE(l.property_id, hl.property_id)
                          LEFT JOIN person pe ON s.table_name = 'person' AND pe.uuid::text = s.record_id
                          LEFT JOIN agent ag ON s.table_name = 'agent' AND ag.uuid::text = s.record_id
                          LEFT JOIN person ap ON ap.uuid = ag.person_id
                          LEFT JOIN agent_role r ON s.table_name = 'agent_role' AND r.uuid::text = s.record_id
                    )
                    SELECT * FROM named
                     ORDER BY %s %s NULLS LAST, changed_at DESC, last_id DESC
                     LIMIT :limit OFFSET :offset
                    """.formatted(column, direction))
                .param("agentId", agentId)
                .param("limit", limit)
                .param("offset", offset)
                .query(HistoryRows.SaveRow.class)
                .list();
    }

    // ---- history for Glade's screens ----------------------------------------------------
    // A change's author is the agent's person. System rows have no agent, so they read "The server".

    /**
     * The days each field of these records changed, with how many changes and who made them.
     * Only edits count: adding a record is shown on its own, not as a change to every field.
     * A map field (customFields, fieldLinks, fieldNotes) also gives each changed entry its own row,
     * named like "customFields.Septic". Days follow the viewer's time zone.
     */
    public List<HistoryRows.ActiveDayRow> findActiveDays(String table, Collection<String> recordIds, String zone, int years) {
        if (recordIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                    SELECT a.record_id,
                           f.field,
                           (a.changed_at AT TIME ZONE :zone)::date AS day,
                           count(*)::int AS changes,
                           string_agg(DISTINCT COALESCE(p.name_full, 'The server'), ', ') AS names
                      FROM audit_log a
                      CROSS JOIN LATERAL (
                            SELECT k.key AS field
                              FROM jsonb_each(a.value_after::jsonb) k
                            UNION ALL
                            SELECT k.key || '.' || e.key
                              FROM jsonb_each(a.value_after::jsonb) k
                             CROSS JOIN LATERAL jsonb_object_keys(
                                   CASE WHEN jsonb_typeof(k.value) = 'object' THEN k.value ELSE '{}'::jsonb END) AS e(key)
                      ) f
                      LEFT JOIN agent ag ON ag.uuid = a.user_id
                      LEFT JOIN person p ON p.uuid = ag.person_id
                     WHERE a.table_name = :table
                       AND a.record_id IN (:ids)
                       AND a.operation = 'UPDATE'
                       AND a.changed_at >= now() - make_interval(years => :years)
                     GROUP BY a.record_id, f.field, day
                     ORDER BY a.record_id, f.field, day
                    """)
                .param("table", table)
                .param("ids", recordIds)
                .param("zone", zone)
                .param("years", years)
                .query(HistoryRows.ActiveDayRow.class)
                .list();
    }

    // Who added each record, and when. A record with no insert row is left out.
    public List<HistoryRows.MarkRow> findAdded(String table, Collection<String> recordIds) {
        if (recordIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                    SELECT DISTINCT ON (a.record_id)
                           a.record_id,
                           a.changed_at,
                           COALESCE(p.name_full, 'The server') AS name
                      FROM audit_log a
                      LEFT JOIN agent ag ON ag.uuid = a.user_id
                      LEFT JOIN person p ON p.uuid = ag.person_id
                     WHERE a.table_name = :table
                       AND a.record_id IN (:ids)
                       AND a.operation = 'INSERT'
                     ORDER BY a.record_id, a.changed_at, a.uuid
                    """)
                .param("table", table)
                .param("ids", recordIds)
                .query(HistoryRows.MarkRow.class)
                .list();
    }

    // Who deleted each record most recently, and when. A record with no delete row is left out.
    public List<HistoryRows.MarkRow> findDeleted(String table, Collection<String> recordIds) {
        if (recordIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                    SELECT DISTINCT ON (a.record_id)
                           a.record_id,
                           a.changed_at,
                           COALESCE(p.name_full, 'The server') AS name
                      FROM audit_log a
                      LEFT JOIN agent ag ON ag.uuid = a.user_id
                      LEFT JOIN person p ON p.uuid = ag.person_id
                     WHERE a.table_name = :table
                       AND a.record_id IN (:ids)
                       AND a.operation = 'DELETE'
                     ORDER BY a.record_id, a.changed_at DESC, a.uuid DESC
                    """)
                .param("table", table)
                .param("ids", recordIds)
                .query(HistoryRows.MarkRow.class)
                .list();
    }

    // The newest change of any kind to any of these records
    public Optional<HistoryRows.MarkRow> findLastEdit(String table, Collection<String> recordIds) {
        if (recordIds.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                    SELECT a.record_id,
                           a.changed_at,
                           COALESCE(p.name_full, 'The server') AS name
                      FROM audit_log a
                      LEFT JOIN agent ag ON ag.uuid = a.user_id
                      LEFT JOIN person p ON p.uuid = ag.person_id
                     WHERE a.table_name = :table
                       AND a.record_id IN (:ids)
                     ORDER BY a.changed_at DESC, a.uuid DESC
                     LIMIT 1
                    """)
                .param("table", table)
                .param("ids", recordIds)
                .query(HistoryRows.MarkRow.class)
                .optional();
    }

    /**
     * One record's rows that touch any of the given fields, newest first.
     * Rows written in one save share a time, so the id (a uuidv7, in time order) breaks ties.
     * Without it, paging could skip or repeat a row.
     * Each field is [name] or [map field, entry], like ["customFields", "Septic"].
     * day, when not null, keeps one day in the viewer's time zone.
     */
    public List<HistoryRows.EntryRow> findEntries(String table, String recordId, List<String[]> fields,
                                                  LocalDate day, String zone, int limit, int offset) {
        // One test per field: is it in the row's before or after values?
        // Only placeholders are built here; every name is bound as a parameter.
        StringBuilder touches = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            String[] field = fields.get(i);
            String path = "-> :top" + i + "::text";
            params.put("top" + i, field[0]);
            if (field.length > 1) {
                path += " -> :entry" + i + "::text";
                params.put("entry" + i, field[1]);
            }
            if (i > 0) {
                touches.append(" OR ");
            }
            touches.append("(a.value_after::jsonb ").append(path).append(") IS NOT NULL")
                    .append(" OR (a.value_before::jsonb ").append(path).append(") IS NOT NULL");
        }
        String onDay = "";
        if (day != null) {
            onDay = "AND (a.changed_at AT TIME ZONE :zone)::date = :day";
            params.put("zone", zone);
            params.put("day", day);
        }
        params.put("table", table);
        params.put("id", recordId);
        params.put("limit", limit);
        params.put("offset", offset);

        return jdbc.sql("""
                    SELECT a.uuid,
                           a.changed_at,
                           COALESCE(p.name_full, 'The server') AS name,
                           a.operation::text AS operation,
                           a.value_before,
                           a.value_after,
                           (SELECT count(*)::int - 1
                              FROM audit_log b
                             WHERE b.correlation_id = a.correlation_id
                               AND b.table_name = a.table_name) AS together
                      FROM audit_log a
                      LEFT JOIN agent ag ON ag.uuid = a.user_id
                      LEFT JOIN person p ON p.uuid = ag.person_id
                     WHERE a.table_name = :table
                       AND a.record_id = :id
                       AND (%s)
                       %s
                     ORDER BY a.changed_at DESC, a.uuid DESC
                     LIMIT :limit OFFSET :offset
                    """.formatted(touches, onDay))
                .params(params)
                .query(HistoryRows.EntryRow.class)
                .list();
    }
}

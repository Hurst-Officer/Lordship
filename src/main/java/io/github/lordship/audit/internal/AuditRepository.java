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

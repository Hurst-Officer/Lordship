package io.github.lordship.audit;

import io.github.lordship.audit.internal.AuditLogRow;
import io.github.lordship.audit.internal.AuditRepository;
import io.github.lordship.audit.internal.HistoryRows;
import io.github.lordship.shared.PageRequest;
import io.github.lordship.shared.PageResult;
import io.github.lordship.shared.UserType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuditService {

    private final ObjectMapper objectMapper;
    private final AuditContext auditContext;
    private final AuditRepository auditRepository;

    public AuditService(AuditContext auditContext, ObjectMapper objectMapper, AuditRepository auditRepository) {
        this.auditContext = auditContext;
        this.objectMapper = objectMapper;
        this.auditRepository = auditRepository;
    }

    private static final Set<String> INSERT_EXCLUDED_KEYS = Set.of("createdAt", "uuid");
    private static final Set<String> DELETE_EXCLUDED_KEYS = Set.of("uuid");

    // Null, blank text and empty maps carry no data, so insert and delete logs leave them out.
    private static boolean hasNoData(Object value) {
        return value == null
                || (value instanceof String s && s.isBlank())
                || (value instanceof Map<?, ?> m && m.isEmpty());
    }

    private static Map<String, Object> sanitizeForInsertLog(Map<String, Object> map) {
        return map.entrySet().stream()
                .filter(e -> !hasNoData(e.getValue()))
                .filter(e -> !INSERT_EXCLUDED_KEYS.contains(e.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    private static Map<String, Object> sanitizeForDeleteLog(Map<String, Object> map) {
        return map.entrySet().stream()
                .filter(e -> !hasNoData(e.getValue()))
                .filter(e -> !DELETE_EXCLUDED_KEYS.contains(e.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    public void recordInsert(String tableName, UUID recordId, Map<String, Object> after) {
        if (auditContext.getUserType() == null) {
            throw new IllegalStateException(
                    "No acting principal for an audit row on " + tableName
                            + " -- use recordSystemInsert for system-authored rows");
        }
        var row = new AuditLogRow(
                auditContext.getCorrelationId(),
                auditContext.getActingUserId(),
                auditContext.getUserType(),
                auditContext.getIpAddress(),
                tableName,
                recordId.toString(),
                OperationType.INSERT,
                null,
                toJson(sanitizeForInsertLog(after))
        );
        auditRepository.save(row);
    }

    public void recordSystemInsert(UUID correlationId, String tableName, UUID recordId, Map<String, Object> after) {
        AuditLogRow row = new AuditLogRow(
                correlationId,
                null,
                UserType.SYSTEM,
                "system",
                tableName,
                recordId.toString(),
                OperationType.INSERT,
                null,
                toJson(sanitizeForInsertLog(after))
        );
        auditRepository.save(row);
    }

    public void recordUpdate(String tableName, UUID recordId, Map<String, Object> before, Map<String, Object> after) {
        var row = new AuditLogRow(
                auditContext.getCorrelationId(),
                auditContext.getActingUserId(),
                auditContext.getUserType(),
                auditContext.getIpAddress(),
                tableName,
                recordId.toString(),
                OperationType.UPDATE,
                toJson(before),
                toJson(after)
        );
        auditRepository.save(row);
    }

    public void recordDelete(String tableName, UUID recordId, Map<String, Object> before) {
        var row = new AuditLogRow(
                auditContext.getCorrelationId(),
                auditContext.getActingUserId(),
                auditContext.getUserType(),
                auditContext.getIpAddress(),
                tableName,
                recordId.toString(),
                OperationType.DELETE,
                toJson(sanitizeForDeleteLog(before)),
                null
        );
        auditRepository.save(row);
    }

    public PageResult<AuditLog> findAgentAuditLogs(UUID agentId, Integer page, Integer pageSize, String sortBy, boolean ascending) {
        PageRequest pageRequest = PageRequest.of(page, pageSize, sortBy, ascending);
        return auditRepository.findAllAgentAuditLogs(agentId, pageRequest);
    }

    // ---- history for Glade's screens ----------------------------------------------------

    // The tables whose history the screens may read
    public static final Set<String> SCREEN_TABLES = Set.of("property", "lot", "mobile_home");

    // The history bars reach back this many years
    public static final int HISTORY_YEARS = 3;

    // One history bar asks for at most this many fields (a field, its link and its note, for example)
    private static final int MAX_FIELDS = 8;

    // The viewer's time zone, like "America/Los_Angeles". Missing or unknown means UTC.
    public static ZoneId zoneOf(String zone) {
        if (zone == null || zone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(zone);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    /**
     * The history a screen loads with its data.
     * shown are the records with bars on the screen: each gets its busy days and who added it.
     * counted only count toward the page's last edit, like the lots on a property page.
     */
    public History.PageHistory pageHistory(List<History.Ref> shown, List<History.Ref> counted, ZoneId zone) {
        List<History.RecordHistory> records = new ArrayList<>();

        for (Map.Entry<String, List<UUID>> table : byTable(shown).entrySet()) {
            List<String> ids = table.getValue().stream().map(UUID::toString).toList();

            // record id -> field -> days
            Map<String, Map<String, List<History.ActiveDay>>> days = new HashMap<>();
            for (HistoryRows.ActiveDayRow row : auditRepository.findActiveDays(table.getKey(), ids, zone.getId(), HISTORY_YEARS)) {
                days.computeIfAbsent(row.recordId(), id -> new LinkedHashMap<>())
                        .computeIfAbsent(row.field(), field -> new ArrayList<>())
                        .add(new History.ActiveDay(row.day(), row.changes(), row.names()));
            }

            Map<String, History.Mark> added = new HashMap<>();
            for (HistoryRows.MarkRow row : auditRepository.findAdded(table.getKey(), ids)) {
                added.put(row.recordId(), new History.Mark(row.name(), row.changedAt()));
            }

            for (UUID id : table.getValue()) {
                String key = id.toString();
                records.add(new History.RecordHistory(table.getKey(), id, added.get(key), days.getOrDefault(key, Map.of())));
            }
        }

        List<History.Ref> everything = new ArrayList<>(shown);
        everything.addAll(counted);
        History.Mark lastEdit = null;
        for (Map.Entry<String, List<UUID>> table : byTable(everything).entrySet()) {
            List<String> ids = table.getValue().stream().map(UUID::toString).toList();
            Optional<HistoryRows.MarkRow> newest = auditRepository.findLastEdit(table.getKey(), ids);
            if (newest.isPresent() && (lastEdit == null || newest.get().changedAt().isAfter(lastEdit.at()))) {
                lastEdit = new History.Mark(newest.get().name(), newest.get().changedAt());
            }
        }

        return new History.PageHistory(lastEdit, records);
    }

    /**
     * Who deleted each record, for the lists of deleted properties and lots.
     * A record with no delete in the audit log is left out of the map.
     */
    public Map<UUID, History.Mark> deletions(String table, Collection<UUID> recordIds) {
        List<String> ids = recordIds.stream().map(UUID::toString).toList();
        Map<UUID, History.Mark> found = new HashMap<>();
        for (HistoryRows.MarkRow row : auditRepository.findDeleted(table, ids)) {
            found.put(UUID.fromString(row.recordId()), new History.Mark(row.name(), row.changedAt()));
        }
        return found;
    }

    /**
     * One record's changes to some of its fields, newest first.
     * fields are the record's own names ("propertyZoning"), or a map entry ("customFields.Septic").
     * day, when not null, keeps only the changes on that day in the viewer's time zone.
     * Empty for a table the screens may not read.
     */
    public History.EntryPage fieldHistory(History.Ref record, List<String> fields, LocalDate day, ZoneId zone,
                                          int page, int pageSize) {
        List<String[]> paths = fields.stream()
                .filter(field -> field != null && !field.isBlank())
                .distinct()
                .limit(MAX_FIELDS)
                .map(field -> field.split("\\.", 2))
                .toList();
        if (!SCREEN_TABLES.contains(record.table()) || paths.isEmpty()) {
            return new History.EntryPage(List.of(), false);
        }

        // one more than a page, to know whether older entries remain
        List<HistoryRows.EntryRow> rows = auditRepository.findEntries(record.table(), record.id().toString(), paths,
                day, zone.getId(), pageSize + 1, Math.max(0, page) * pageSize);

        List<History.Entry> entries = rows.stream()
                .limit(pageSize)
                .map(row -> toEntry(row, paths))
                .toList();
        return new History.EntryPage(entries, rows.size() > pageSize);
    }

    // One audit row as an entry, keeping only the asked-for fields
    private History.Entry toEntry(HistoryRows.EntryRow row, List<String[]> paths) {
        Map<String, Object> before = readMap(row.valueBefore());
        Map<String, Object> after = readMap(row.valueAfter());
        List<History.Change> changes = new ArrayList<>();
        for (String[] path : paths) {
            if (has(before, path) || has(after, path)) {
                changes.add(new History.Change(String.join(".", path), valueAt(before, path), valueAt(after, path)));
            }
        }
        return new History.Entry(row.uuid(), row.changedAt(), row.name(), OperationType.valueOf(row.operation()),
                changes, row.together());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, Map.class);
    }

    // Whether the values hold this field: [name], or [map field, entry]
    private static boolean has(Map<String, Object> values, String[] path) {
        if (path.length == 1) {
            return values.containsKey(path[0]);
        }
        return values.get(path[0]) instanceof Map<?, ?> map && map.containsKey(path[1]);
    }

    private static Object valueAt(Map<String, Object> values, String[] path) {
        if (path.length == 1) {
            return values.get(path[0]);
        }
        return values.get(path[0]) instanceof Map<?, ?> map ? map.get(path[1]) : null;
    }

    // Groups records by their table, keeping the order they came in
    private static Map<String, List<UUID>> byTable(List<History.Ref> refs) {
        Map<String, List<UUID>> grouped = new LinkedHashMap<>();
        for (History.Ref ref : refs) {
            grouped.computeIfAbsent(ref.table(), table -> new ArrayList<>()).add(ref.id());
        }
        return grouped;
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(map);
    }
}
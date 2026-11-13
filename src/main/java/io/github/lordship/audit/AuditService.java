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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    // ---- an agent's own activity -----------------------------------------------------------

    /**
     * The agent's own saves, for "My activity" on their own page. sort is when, kind or record
     * (anything else is when); a page is limit saves from offset.
     * Each save of a table the screens show carries its first record's values, field by field.
     * The caller hides the saves under properties the agent may no longer see (Save.withoutRecord),
     * and the values of kinds of record the agent may no longer view (Save.withValuesHidden).
     */
    public History.SavePage ownActivity(UUID agentId, String sort, boolean ascending, int offset, int limit) {
        List<HistoryRows.SaveRow> rows = auditRepository.findOwnSaves(agentId, sort, ascending, limit + 1, Math.max(0, offset));
        List<HistoryRows.SaveRow> shown = rows.stream().limit(limit).toList();
        Map<String, String> roleNames = roleNamesIn(shown);
        List<History.Save> saves = shown.stream()
                .map(row -> new History.Save(
                        row.saveId(), row.changedAt(), row.tableName(), OperationType.valueOf(row.operation()),
                        row.records(), row.recordId(),
                        row.fields() == null || row.fields().isBlank() ? List.of() : List.of(row.fields().split(",")),
                        changesOf(row, roleNames), row.sameForAll(),
                        row.recordName(), row.propertyId(), row.propertyName(), row.lotId(), false))
                .toList();
        return new History.SavePage(saves, rows.size() > limit);
    }

    // Fields "My activity" leaves out: ids and bookkeeping the worker never sees on a screen
    private static final Set<String> NOT_IN_ACTIVITY = Set.of(
            "uuid", "createdAt", "createdBy", "propertyId", "lotId", "personId", "propertyManager",
            "tokensValidFrom", "sortOrder", "permissibleAgreementTypes");

    // Fields "My activity" names without their values: secret, a link to another record, or too big for a line
    private static final Set<String> WITHHELD_IN_ACTIVITY = Set.of(
            "social", "agent_password", "emergencyContact", "shapeData");

    // One save's first record, field by field. A map field gives one change per entry ("customFields.Septic").
    // Empty for a table the screens do not show: its line keeps only its field names.
    private List<History.Edit> changesOf(HistoryRows.SaveRow row, Map<String, String> roleNames) {
        if (!SCREEN_TABLES.contains(row.tableName())) {
            return List.of();
        }
        Map<String, Object> before = readMap(row.valueBefore());
        Map<String, Object> after = readMap(row.valueAfter());
        Set<String> keys = new LinkedHashSet<>(after.keySet());
        keys.addAll(before.keySet());

        List<History.Edit> changes = new ArrayList<>();
        for (String key : keys) {
            if (NOT_IN_ACTIVITY.contains(key)) {
                continue;
            }
            if (WITHHELD_IN_ACTIVITY.contains(key)) {
                changes.add(new History.Edit(key, null, null, true));
                continue;
            }
            Object was = before.get(key);
            Object now = after.get(key);
            if (was instanceof Map<?, ?> || now instanceof Map<?, ?>) {
                Map<?, ?> wasEntries = was instanceof Map<?, ?> map ? map : Map.of();
                Map<?, ?> nowEntries = now instanceof Map<?, ?> map ? map : Map.of();
                Set<Object> entries = new LinkedHashSet<>(nowEntries.keySet());
                entries.addAll(wasEntries.keySet());
                for (Object entry : entries) {
                    String name = String.valueOf(entry);
                    // a role given to a person is logged by the role's id; the worker knows it by its name
                    if ("agent".equals(row.tableName()) && "roles".equals(key)) {
                        name = roleNames.getOrDefault(name, name);
                    }
                    changes.add(new History.Edit(key + "." + name, wasEntries.get(entry), nowEntries.get(entry), false));
                }
            } else {
                changes.add(new History.Edit(key, was, now, false));
            }
        }
        return changes;
    }

    // The names of the roles given or taken in these saves (agent rows keep "roles" by role id)
    private Map<String, String> roleNamesIn(List<HistoryRows.SaveRow> rows) {
        Set<String> ids = new HashSet<>();
        for (HistoryRows.SaveRow row : rows) {
            if (!"agent".equals(row.tableName())) {
                continue;
            }
            for (String json : new String[] { row.valueBefore(), row.valueAfter() }) {
                if (readMap(json).get("roles") instanceof Map<?, ?> roles) {
                    roles.keySet().forEach(id -> ids.add(String.valueOf(id)));
                }
            }
        }
        return auditRepository.findRoleNames(ids);
    }

    // ---- history for Glade's screens ----------------------------------------------------

    // The tables whose history the screens may read. agent_role and agent are the Roles screens
    // (access RoleHistory writes their "permissions", "holders" and "roles" entries).
    // person and agent are also the agent's own page (GET /api/screens/self), for their own record only.
    // The document tables are the Documents screens: a document, its sections, clauses and styles, and the parks using it.
    // lot_permissible_agreement_type and terms_template are the Rates and deals screens: what a lot offers and at
    // what rates, and a property's deals.
    public static final Set<String> SCREEN_TABLES = Set.of("property", "lot", "mobile_home", "agent_role", "agent", "person",
            "document_template", "document_section", "template_clause", "document_style",
            "property_document_assignment", "property_document_customization",
            "lot_permissible_agreement_type", "terms_template");

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

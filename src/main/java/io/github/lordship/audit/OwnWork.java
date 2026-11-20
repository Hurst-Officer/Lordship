package io.github.lordship.audit;

import io.github.lordship.audit.internal.AuditRepository;
import io.github.lordship.audit.internal.HistoryRows;
import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.shared.PropertyScope;
import io.github.lordship.shared.RuleConflict;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Own recent work: whoever added a record may delete and restore that record for a few days,
 * without the delete permission. An office worker who can add a lot can then undo adding the lot.
 *
 * <p>The rule widens who may delete, never what may be deleted: every refusal a service makes
 * (a lot with an active tenancy, a person who signs in as an agent) still applies afterwards.
 *
 * <p>An agent may take a record back when all of these hold:
 * <ol>
 *   <li>audit_log says the agent added the record (the record's INSERT row carries the agent's id).
 *       A record with no INSERT row (a seed, an import, psql) has no maker, so the rule never covers that record.</li>
 *   <li>the agent still holds the permission to add that kind of record</li>
 *   <li>the record's property is still in the agent's scope (a person belongs to no property)</li>
 *   <li>the record was added no more than lordship.own-work.days ago (7 unless set)</li>
 *   <li>nobody else has touched the record since: no audit row on the record by anyone else (an edit, a delete,
 *       a restore), and nothing of anyone else's under the record. Under the record means every live row of the
 *       kind's under tables: a row someone else added, or a row someone else changed, ends the right. Where an under
 *       table is a kind of record as well, the rows under those rows count in the same way, however deep: a home
 *       a coworker put on one of the lots ends the right to take back the lots' property.
 *       A record someone else deleted stays deleted: the maker restores only what the maker deleted.</li>
 * </ol>
 *
 * <p>Used from an endpoint's permission check, on delete and on restore:
 * <pre>
 *   {@literal @}PreAuthorize("hasAuthority('lots:delete') or @ownWork.madeRecently('lot', #uuid, authentication)")
 * </pre>
 * Conditions 1 to 3 answer false, which is the ordinary 403 of a missing permission. Conditions 4 and 5 are
 * refused with a code (409: own_work.window_passed, own_work.built_on, own_work.not_deleted_by_you), because the
 * record is the agent's own and the agent should be told why the record can no longer be taken back.
 *
 * <p>A NEW KIND OF RECORD needs one entry in KINDS below, and the check above on the kind's delete and restore
 * endpoints. Nothing else in this class changes. The names in KINDS are written here and never come from a request:
 * the table names, column names and property queries in KINDS become part of a query's text, so Kind and Under are
 * private and the table an endpoint passes is only ever the key that finds a Kind.
 * The kind's list of deleted records uses {@link #restorable}, and the kind's screen answer uses {@link #mine}
 * (see ScreenResponses). OwnWorkAnnotationsTest fails when an endpoint names a table KINDS does not hold.
 *
 * <p>A NEW TABLE THAT POINTS AT A COVERED RECORD (a new thing a coworker can hang on a lot) needs one Under in
 * that kind's entry, or the maker could take the record back from under the coworker's work.
 *
 * <p>lordship.own-work.days of 0 (or less) switches the rule off: every delete then needs the delete permission.
 */
@Component("ownWork")
public class OwnWork {

    /**
     * One kind of record the rule covers.
     *
     * @param table            the record's table, which is also the record's table_name in audit_log
     * @param createPermission the permission that lets an agent add a record of this kind
     * @param deletePermission the permission that lets an agent delete any record of this kind
     * @param propertySql      finds the properties of the records in :ids, as the columns id and property_id;
     *                         null for a kind tied to no property
     * @param under            the tables whose rows hang under a record of this kind
     */
    private record Kind(String table, String createPermission, String deletePermission, String propertySql,
                        List<Under> under) { }

    /**
     * A table whose rows hang under a record, and the column that points at the record.
     * The table needs a uuid column, and the table's inserts, edits and deletes are logged under the table's own
     * name with the row's uuid. softDeleted says the table has a deleted_at column, so that deleted rows are
     * left out; false is for a table whose rows are removed outright (every row there is live).
     */
    private record Under(String table, String column, boolean softDeleted) {
        Under(String table, String column) {
            this(table, column, true);
        }
    }

    private static final Map<String, Kind> KINDS = Stream.of(
            // Not under a property here: site_map and map_feature. The map builder writes features with no audit
            // row, so those rows cannot be told apart by who added them; map work on a property shows on the
            // property's lots instead (a placed shape is an edit of the lot).
            new Kind("property", "properties:create", "properties:delete",
                    "SELECT uuid AS id, uuid AS property_id FROM property WHERE uuid IN (:ids)",
                    List.of(new Under("lot", "property_id"),
                            new Under("terms_template", "property"),
                            new Under("property_document_assignment", "property"),
                            new Under("meter_main", "property_id"),
                            new Under("submeter", "property_id"),
                            new Under("utility_rate_group", "property_id"),
                            new Under("utility_bill", "property_id"),
                            new Under("utility_cycle", "property_id"))),
            new Kind("lot", "lots:create", "lots:delete",
                    "SELECT uuid AS id, property_id FROM lot WHERE uuid IN (:ids)",
                    List.of(new Under("mobile_home", "lot_id"),
                            new Under("stick_built", "lot_id"),
                            new Under("tenancy", "lot_id"),
                            new Under("submeter_lot", "lot_id"),
                            // what the lot offers and at what rates; a removed offer is removed outright
                            new Under("lot_permissible_agreement_type", "lot_id", false))),
            new Kind("mobile_home", "homes:create", "homes:delete",
                    "SELECT h.uuid AS id, l.property_id FROM mobile_home h JOIN lot l ON l.uuid = h.lot_id WHERE h.uuid IN (:ids)",
                    List.of(new Under("mobile_home_secured_party", "mobile_home_id"))),
            // a person belongs to no property, so there is no scope to check
            new Kind("person", "persons:create", "persons:delete",
                    null,
                    List.of(new Under("tenant", "person_id"),
                            new Under("occupant", "person_id"),
                            new Under("tenancy_interested_party", "person_id"),
                            new Under("mobile_home_secured_party", "person_id"),
                            new Under("agent", "person_id"))),
            // Glade's Tenancies: a tenancy, and each person's stay on a tenancy. Entered on 2026-11-20 from the
            // migrations (V7, V8, V10, V11); the tenancy and tenants endpoints do not ask this class yet.
            new Kind("tenancy", "tenancy:create", "tenancy:delete",
                    "SELECT t.uuid AS id, l.property_id FROM tenancy t JOIN lot l ON l.uuid = t.lot_id WHERE t.uuid IN (:ids)",
                    List.of(new Under("tenant", "tenancy_id"),
                            new Under("occupant", "tenancy_id"),
                            new Under("tenancy_interested_party", "tenancy_id"),
                            new Under("tenancy_charge_term", "tenancy"),
                            // the tenancy's account is added with the tenancy, by the same agent
                            new Under("account", "tenancy_id"),
                            new Under("vehicle", "tenancy_id"),
                            new Under("security_deposit", "tenancy"),
                            // an instrument is never deleted, only abandoned
                            new Under("instrument", "tenancy", false))),
            new Kind("tenant", "tenants:create", "tenants:delete", stayPropertySql("tenant"), List.of()),
            new Kind("occupant", "tenants:create", "tenants:delete", stayPropertySql("occupant"), List.of()),
            new Kind("tenancy_interested_party", "tenants:create", "tenants:delete",
                    stayPropertySql("tenancy_interested_party"), List.of())
    ).collect(Collectors.toUnmodifiableMap(Kind::table, Function.identity()));

    // The property of a person's stay on a tenancy: the property of the lot of the stay's tenancy
    private static String stayPropertySql(String table) {
        return "SELECT m.uuid AS id, l.property_id FROM " + table + " m"
                + " JOIN tenancy t ON t.uuid = m.tenancy_id JOIN lot l ON l.uuid = t.lot_id WHERE m.uuid IN (:ids)";
    }

    // What the rule says about one record and one agent
    private enum Verdict {
        // the agent may take the record back
        OPEN,
        // the agent did not add the record, lost the permission to add, or lost the property: the ordinary 403
        NOT_THEIRS,
        // the agent added the record too long ago
        WINDOW_PASSED,
        // someone else changed or deleted the record, or added or changed something under the record
        BUILT_ON
    }

    private final AuditRepository auditRepository;
    private final int days;

    public OwnWork(AuditRepository auditRepository, @Value("${lordship.own-work.days:7}") int days) {
        this.auditRepository = auditRepository;
        this.days = days;
    }

    /**
     * Whether the signed-in agent may delete or restore this record as the agent's own recent work.
     * False when the record is not the agent's to take back (the caller answers 403).
     * Throws RuleConflict (409) when the record is the agent's own but can no longer be taken back.
     */
    public boolean madeRecently(String table, UUID id, Authentication authentication) {
        return id != null && madeAllRecently(table, List.of(id), authentication);
    }

    /**
     * The same for a selection of records, like the picked lots of a bulk delete: true only when every record
     * passes. One record that is not the agent's answers false for the whole selection, whatever else is wrong
     * with the selection; otherwise one record that can no longer be taken back throws for the whole selection.
     */
    public boolean madeAllRecently(String table, Collection<UUID> ids, Authentication authentication) {
        Map<UUID, Verdict> verdicts = verdicts(table, ids, authentication);
        Collection<Verdict> found = verdicts.values();
        if (found.isEmpty() || found.contains(Verdict.NOT_THEIRS)) {
            return false;
        }
        if (found.contains(Verdict.WINDOW_PASSED)) {
            throw RuleConflict.of("own_work.window_passed", days);
        }
        if (found.contains(Verdict.BUILT_ON)) {
            // The agent hears "someone else deleted this" where that is what happened, and "changed" otherwise.
            // Only a refusal asks, so the screens' mine never pays for the extra reads.
            UUID makerId = agentOf(authentication).agentUuid();
            for (Map.Entry<UUID, Verdict> entry : verdicts.entrySet()) {
                if (entry.getValue() == Verdict.BUILT_ON && deletedByAnother(table, entry.getKey(), makerId)) {
                    throw RuleConflict.of("own_work.not_deleted_by_you");
                }
            }
            throw RuleConflict.of("own_work.built_on");
        }
        return true;
    }

    /**
     * Of these records, the ones the signed-in agent may take back right now as own recent work. Never throws.
     * The screens send this as "mine", so Glade shows Delete on those records to an agent without the delete
     * permission. Empty for an agent who holds the kind's delete permission: that agent may delete every record
     * of the kind and needs no mark, so the log is not read for that agent.
     */
    public Set<UUID> mine(String table, Collection<UUID> ids, Authentication authentication) {
        Kind kind = KINDS.get(table);
        if (kind != null && holds(authentication, kind.deletePermission())) {
            return Set.of();
        }
        return verdicts(table, ids, authentication).entrySet().stream()
                .filter(entry -> entry.getValue() == Verdict.OPEN)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * What a list of deleted records shows the signed-in agent: every deleted record for an agent who holds the
     * kind's delete permission, and otherwise only the records the agent may restore as own recent work (the
     * records the agent added and deleted). idOf gives a record's id. An unknown kind answers an empty list.
     */
    public <T> List<T> restorable(String table, List<T> deleted, Function<T, UUID> idOf, Authentication authentication) {
        Kind kind = KINDS.get(table);
        if (kind == null) {
            return List.of();
        }
        if (holds(authentication, kind.deletePermission())) {
            return deleted;
        }
        Set<UUID> own = mine(table, deleted.stream().map(idOf).toList(), authentication);
        return deleted.stream().filter(row -> own.contains(idOf.apply(row))).toList();
    }

    /** Whether the rule knows this table as a kind of record. For OwnWorkAnnotationsTest. */
    public static boolean covers(String table) {
        return KINDS.containsKey(table);
    }

    // The rule for each record. Empty for an unknown kind, no records, or a caller who is not an agent.
    // The log is read for all the records at once: a page of 150 lots costs one query when none of the lots is
    // the agent's own recent work, and a few queries for each under table when some are.
    private Map<UUID, Verdict> verdicts(String table, Collection<UUID> ids, Authentication authentication) {
        Kind kind = KINDS.get(table);
        AgentPrincipal agent = agentOf(authentication);
        if (kind == null || ids == null || ids.isEmpty() || agent == null) {
            return Map.of();
        }
        Map<UUID, Verdict> verdicts = new LinkedHashMap<>();
        for (UUID id : ids) {
            if (id != null) {
                verdicts.put(id, Verdict.NOT_THEIRS);
            }
        }
        // days of 0 or less switches the rule off
        if (verdicts.isEmpty() || days <= 0) {
            return verdicts;
        }

        // conditions 1 to 3: the agent added the record, may still add that kind, and still sees the property
        if (!holds(authentication, kind.createPermission())) {
            return verdicts;
        }
        UUID makerId = agent.agentUuid();
        Map<UUID, OffsetDateTime> added = new LinkedHashMap<>();
        for (HistoryRows.MakerRow row : auditRepository.findMakers(kind.table(), strings(verdicts.keySet()))) {
            if (makerId.equals(row.userId())) {
                added.putIfAbsent(UUID.fromString(row.recordId()), row.changedAt());
            }
        }
        Set<UUID> theirs = inScope(kind, added.keySet(), agent.propertyScope());

        // condition 4: added within the days
        OffsetDateTime oldest = OffsetDateTime.now().minusDays(days);
        Set<UUID> recent = new LinkedHashSet<>();
        for (UUID id : theirs) {
            if (added.get(id).isBefore(oldest)) {
                verdicts.put(id, Verdict.WINDOW_PASSED);
            } else {
                recent.add(id);
            }
        }

        // condition 5: nobody else has touched the record, or anything under the record
        Set<UUID> builtOn = new HashSet<>();
        for (String id : auditRepository.findTouchedByAnother(kind.table(), strings(recent), makerId)) {
            builtOn.add(UUID.fromString(id));
        }
        builtOn.addAll(withWorkOfAnotherUnder(kind, recent, makerId));
        for (UUID id : recent) {
            verdicts.put(id, builtOn.contains(id) ? Verdict.BUILT_ON : Verdict.OPEN);
        }
        return verdicts;
    }

    // Of these records of one kind, the ones with someone else's work under the record: a live row of one of the
    // kind's under tables that someone else added or changed. Where the under table is a kind of record as well,
    // the rows under those rows are looked at in the same way, so the answer covers every level below the record
    // (a property's lots, the homes and tenancies on those lots, the people on those tenancies).
    private Set<UUID> withWorkOfAnotherUnder(Kind kind, Collection<UUID> ids, UUID makerId) {
        Set<UUID> found = new HashSet<>();
        if (ids.isEmpty()) {
            return found;
        }
        for (Under under : kind.under()) {
            found.addAll(auditRepository.findWithRowOfAnother(
                    under.table(), under.column(), under.softDeleted(), ids, makerId));

            Kind inner = KINDS.get(under.table());
            if (inner != null && !inner.under().isEmpty()) {
                // row under the record -> the record
                Map<UUID, UUID> recordOf = auditRepository.findLiveUnder(under.table(), under.column(), ids);
                for (UUID row : withWorkOfAnotherUnder(inner, recordOf.keySet(), makerId)) {
                    found.add(recordOf.get(row));
                }
            }
        }
        return found;
    }

    // Whether the newest row on the record is a delete by someone else. This tells a record someone else deleted
    // from a record someone else changed, so the agent hears "someone else deleted this" and not only "changed".
    private boolean deletedByAnother(String table, UUID id, UUID makerId) {
        Optional<HistoryRows.LastRow> last = auditRepository.findLast(table, id.toString());
        return last.isPresent() && "DELETE".equals(last.get().operation()) && !makerId.equals(last.get().userId());
    }

    // Of these records, the ones whose property the agent may see. A kind tied to no property is always in scope.
    // A record whose property cannot be found (a home that sits on no lot) is in scope only for an agent who
    // sees every property, the same answer HomeService.inScope gives.
    private Set<UUID> inScope(Kind kind, Set<UUID> ids, PropertyScope scope) {
        if (kind.propertySql() == null || scope.isAll() || ids.isEmpty()) {
            return ids;
        }
        Map<UUID, UUID> properties = auditRepository.findProperties(kind.propertySql(), ids);
        return ids.stream()
                .filter(id -> properties.containsKey(id) && scope.allows(properties.get(id)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // The signed-in agent, or null for a caller who is not an agent
    private static AgentPrincipal agentOf(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AgentPrincipal agent ? agent : null;
    }

    // Whether the caller holds this permission
    private static boolean holds(Authentication authentication, String permission) {
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(permission::equals);
    }

    private static List<String> strings(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }
}

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
 *       a restore), and no live record under the record that someone else added (a lot under the property,
 *       a home on the lot). A record someone else deleted stays deleted: the maker restores only what the maker deleted.</li>
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
 * The kind's list of deleted records and the kind's screen answer use {@link #mine} (see ScreenResponses).
 */
@Component("ownWork")
public class OwnWork {

    /**
     * One kind of record the rule covers.
     *
     * @param table            the record's table, which is also the record's table_name in audit_log
     * @param createPermission the permission that lets an agent add a record of this kind
     * @param propertySql      finds the properties of the records in :ids, as the columns id and property_id;
     *                         null for a kind tied to no property
     * @param under            the tables whose rows hang under a record of this kind
     */
    private record Kind(String table, String createPermission, String propertySql, List<Under> under) { }

    /**
     * A table whose rows hang under a record, and the column that points at the record.
     * The table needs a uuid column and a deleted_at column, and the table's inserts are logged under the table's own name.
     */
    private record Under(String table, String column) { }

    private static final Map<String, Kind> KINDS = Stream.of(
            new Kind("property", "properties:create",
                    "SELECT uuid AS id, uuid AS property_id FROM property WHERE uuid IN (:ids)",
                    List.of(new Under("lot", "property_id"),
                            new Under("terms_template", "property"))),
            new Kind("lot", "lots:create",
                    "SELECT uuid AS id, property_id FROM lot WHERE uuid IN (:ids)",
                    List.of(new Under("mobile_home", "lot_id"),
                            new Under("stick_built", "lot_id"),
                            new Under("tenancy", "lot_id"))),
            new Kind("mobile_home", "homes:create",
                    "SELECT h.uuid AS id, l.property_id FROM mobile_home h JOIN lot l ON l.uuid = h.lot_id WHERE h.uuid IN (:ids)",
                    List.of(new Under("mobile_home_secured_party", "mobile_home_id"))),
            // a person belongs to no property, so there is no scope to check
            new Kind("person", "persons:create",
                    null,
                    List.of(new Under("tenant", "person_id"),
                            new Under("occupant", "person_id"),
                            new Under("tenancy_interested_party", "person_id"),
                            new Under("mobile_home_secured_party", "person_id"),
                            new Under("agent", "person_id"))),
            // Glade's Tenancies: a tenancy, and each person's stay on a tenancy. Entered on 2026-11-20 from the
            // migrations (V7, V10); the tenancy and tenants endpoints do not ask this class yet.
            new Kind("tenancy", "tenancy:create",
                    "SELECT t.uuid AS id, l.property_id FROM tenancy t JOIN lot l ON l.uuid = t.lot_id WHERE t.uuid IN (:ids)",
                    List.of(new Under("tenant", "tenancy_id"),
                            new Under("occupant", "tenancy_id"),
                            new Under("tenancy_interested_party", "tenancy_id"),
                            new Under("tenancy_charge_term", "tenancy"))),
            new Kind("tenant", "tenants:create", stayPropertySql("tenant"), List.of()),
            new Kind("occupant", "tenants:create", stayPropertySql("occupant"), List.of()),
            new Kind("tenancy_interested_party", "tenants:create", stayPropertySql("tenancy_interested_party"), List.of())
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
        // someone else deleted the record
        DELETED_BY_ANOTHER,
        // someone else changed the record, or added a record under the record
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
        Collection<Verdict> found = verdicts(table, ids, authentication).values();
        if (found.isEmpty() || found.contains(Verdict.NOT_THEIRS)) {
            return false;
        }
        if (found.contains(Verdict.WINDOW_PASSED)) {
            throw RuleConflict.of("own_work.window_passed", days);
        }
        if (found.contains(Verdict.DELETED_BY_ANOTHER)) {
            throw RuleConflict.of("own_work.not_deleted_by_you");
        }
        if (found.contains(Verdict.BUILT_ON)) {
            throw RuleConflict.of("own_work.built_on");
        }
        return true;
    }

    /**
     * Of these records, the ones the signed-in agent may take back right now. Never throws.
     * The screens send this as "mine", so Glade shows Delete on those records to an agent without the delete
     * permission; the lists of deleted records use this to show such an agent the agent's own deletions only.
     */
    public Set<UUID> mine(String table, Collection<UUID> ids, Authentication authentication) {
        return verdicts(table, ids, authentication).entrySet().stream()
                .filter(entry -> entry.getValue() == Verdict.OPEN)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // The rule for each record. Empty for an unknown kind, no records, or a caller who is not an agent.
    // The log is read for all the records at once, so a page of 150 lots costs a handful of queries.
    private Map<UUID, Verdict> verdicts(String table, Collection<UUID> ids, Authentication authentication) {
        Kind kind = KINDS.get(table);
        if (kind == null || ids == null || ids.isEmpty()
                || !(authentication != null && authentication.getPrincipal() instanceof AgentPrincipal agent)) {
            return Map.of();
        }
        Map<UUID, Verdict> verdicts = new LinkedHashMap<>();
        for (UUID id : ids) {
            if (id != null) {
                verdicts.put(id, Verdict.NOT_THEIRS);
            }
        }
        if (verdicts.isEmpty()) {
            return verdicts;
        }

        // conditions 1 to 3: the agent added the record, may still add that kind, and still sees the property
        boolean mayAdd = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(kind.createPermission()::equals);
        if (!mayAdd) {
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

        // condition 5: nobody else has touched the record, or added a record under the record
        Set<String> touched = auditRepository.findTouchedByAnother(kind.table(), strings(recent), makerId);
        Set<UUID> builtOn = new HashSet<>();
        for (Under under : kind.under()) {
            builtOn.addAll(auditRepository.findWithRowByAnother(under.table(), under.column(), recent, makerId));
        }
        for (UUID id : recent) {
            if (touched.contains(id.toString())) {
                verdicts.put(id, deletedByAnother(kind, id, makerId) ? Verdict.DELETED_BY_ANOTHER : Verdict.BUILT_ON);
            } else if (builtOn.contains(id)) {
                verdicts.put(id, Verdict.BUILT_ON);
            } else {
                verdicts.put(id, Verdict.OPEN);
            }
        }
        return verdicts;
    }

    // Whether the newest row on the record is a delete by someone else. This tells a record someone else deleted
    // from a record someone else changed, so the agent hears "someone else deleted this" and not only "changed".
    private boolean deletedByAnother(Kind kind, UUID id, UUID makerId) {
        Optional<HistoryRows.LastRow> last = auditRepository.findLast(kind.table(), id.toString());
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

    private static List<String> strings(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }
}

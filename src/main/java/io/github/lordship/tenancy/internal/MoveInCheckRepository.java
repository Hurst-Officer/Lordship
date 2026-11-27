package io.github.lordship.tenancy.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * move_in_check rows: the office's list for a move-in's signed lease, and the requests the office adds by hand.
 * Writes go through MoveInChecks, which records them in the audit log.
 *
 * <p>The names of the agents who decided an item are read from the agent and person tables by name, the way the
 * audit log reads them, so a list shows who ticked an item without a second call.
 */
@Repository
public class MoveInCheckRepository {

    // The columns a change may set, by the name MoveInChecks uses (the row's component names)
    private static final Map<String, String> SETTABLE = Map.ofEntries(
            Map.entry("state", "state"),
            Map.entry("note", "note"),
            Map.entry("decidedBy", "decided_by"),
            Map.entry("decidedAt", "decided_at"),
            Map.entry("machineState", "machine_state"),
            Map.entry("machineFound", "machine_found"),
            Map.entry("machineAt", "machine_at"),
            Map.entry("answer", "answer"),
            Map.entry("answeredBy", "answered_by"),
            Map.entry("answeredAt", "answered_at"));

    private final JdbcClient jdbc;

    public MoveInCheckRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // One row of move_in_check. Component order matches the column order in V7; the names are the ones the audit
    // log keeps the changes under.
    public record MoveInCheckRow(UUID uuid, UUID tenancyId, String item, String state, String note, UUID decidedBy,
                                 OffsetDateTime decidedAt, String machineState, String machineFound,
                                 OffsetDateTime machineAt, String question, String answer, UUID answeredBy,
                                 OffsetDateTime answeredAt, OffsetDateTime createdAt, UUID createdBy,
                                 OffsetDateTime deletedAt) { }

    // The live rows of a move-in: the fixed items decided so far and the live requests, the oldest first
    public List<MoveInCheckRow> findByTenancy(UUID tenancyId) {
        return jdbc.sql("""
                SELECT * FROM move_in_check
                WHERE tenancy_id = :tenancyId AND deleted_at IS NULL
                ORDER BY created_at, uuid""")
                .param("tenancyId", tenancyId)
                .query(MoveInCheckRow.class)
                .list();
    }

    // The live row of one fixed item of a move-in
    public Optional<MoveInCheckRow> findFixed(UUID tenancyId, String item) {
        return jdbc.sql("""
                SELECT * FROM move_in_check
                WHERE tenancy_id = :tenancyId AND item = :item AND deleted_at IS NULL""")
                .param("tenancyId", tenancyId)
                .param("item", item)
                .query(MoveInCheckRow.class)
                .optional();
    }

    // One row, deleted or not
    public Optional<MoveInCheckRow> findById(UUID uuid) {
        return jdbc.sql("SELECT * FROM move_in_check WHERE uuid = :uuid")
                .param("uuid", uuid)
                .query(MoveInCheckRow.class)
                .optional();
    }

    // The tenancy a row belongs to, deleted or not
    public Optional<UUID> tenancyOf(UUID uuid) {
        return jdbc.sql("SELECT tenancy_id FROM move_in_check WHERE uuid = :uuid")
                .param("uuid", uuid)
                .query(UUID.class)
                .optional();
    }

    /**
     * The live row of a fixed item, made PENDING first when the item has none. Empty only when another writer
     * made the row between this insert and the read, and then deleted the row: the caller treats that as gone.
     * inserted tells the caller whether this call made the row (for the audit log).
     */
    public record Ensured(MoveInCheckRow row, boolean inserted) { }

    public Optional<Ensured> ensureFixed(UUID tenancyId, String item, UUID createdBy) {
        Optional<MoveInCheckRow> made = jdbc.sql("""
                INSERT INTO move_in_check (tenancy_id, item, created_by)
                VALUES (:tenancyId, :item, :createdBy)
                ON CONFLICT (tenancy_id, item) WHERE item <> 'REQUEST' AND deleted_at IS NULL DO NOTHING
                RETURNING *""")
                .param("tenancyId", tenancyId)
                .param("item", item)
                .param("createdBy", createdBy)
                .query(MoveInCheckRow.class)
                .optional();
        if (made.isPresent()) {
            return Optional.of(new Ensured(made.get(), true));
        }
        return findFixed(tenancyId, item).map(row -> new Ensured(row, false));
    }

    // A new request: the office's question for the park
    public MoveInCheckRow insertRequest(UUID tenancyId, String question, UUID createdBy) {
        return jdbc.sql("""
                INSERT INTO move_in_check (tenancy_id, item, question, created_by)
                VALUES (:tenancyId, 'REQUEST', :question, :createdBy)
                RETURNING *""")
                .param("tenancyId", tenancyId)
                .param("question", question)
                .param("createdBy", createdBy)
                .query(MoveInCheckRow.class)
                .single();
    }

    /**
     * Sets some columns of a live row. changes is keyed by the row's component names (see SETTABLE); the caller has
     * checked the values. Empty when the row is gone.
     */
    public Optional<MoveInCheckRow> update(UUID uuid, Map<String, Object> changes) {
        Map<String, Object> known = new LinkedHashMap<>();
        changes.forEach((name, value) -> {
            if (!SETTABLE.containsKey(name)) {
                throw new IllegalArgumentException("A move-in check has no settable column " + name);
            }
            known.put(name, value);
        });
        if (known.isEmpty()) {
            return findById(uuid).filter(row -> row.deletedAt() == null);
        }
        String sets = known.keySet().stream()
                .map(name -> SETTABLE.get(name) + " = :" + name)
                .collect(Collectors.joining(", "));
        JdbcClient.StatementSpec statement = jdbc.sql("""
                UPDATE move_in_check SET %s
                WHERE uuid = :uuid AND deleted_at IS NULL
                RETURNING *""".formatted(sets))
                .param("uuid", uuid);
        for (Map.Entry<String, Object> change : known.entrySet()) {
            statement = statement.param(change.getKey(), change.getValue());
        }
        return statement.query(MoveInCheckRow.class).optional();
    }

    // Marks a request deleted. Empty when the row is gone already or is not a request.
    public Optional<MoveInCheckRow> deleteRequest(UUID uuid) {
        return jdbc.sql("""
                UPDATE move_in_check SET deleted_at = now()
                WHERE uuid = :uuid AND item = 'REQUEST' AND deleted_at IS NULL
                RETURNING *""")
                .param("uuid", uuid)
                .query(MoveInCheckRow.class)
                .optional();
    }

    // Brings a deleted request back. Empty when the row is live already or is not a request.
    public Optional<MoveInCheckRow> restoreRequest(UUID uuid) {
        return jdbc.sql("""
                UPDATE move_in_check SET deleted_at = NULL
                WHERE uuid = :uuid AND item = 'REQUEST' AND deleted_at IS NOT NULL
                RETURNING *""")
                .param("uuid", uuid)
                .query(MoveInCheckRow.class)
                .optional();
    }

    private record AgentName(UUID agentId, String nameFull) { }

    // The full names of some agents, by the agent's id. An agent with no name on file is left out.
    public Map<UUID, String> names(Collection<UUID> agentIds) {
        Set<UUID> wanted = agentIds.stream().filter(id -> id != null).collect(Collectors.toSet());
        // a HashMap even when empty: the caller looks names up by ids that may be null
        Map<UUID, String> found = new HashMap<>();
        if (wanted.isEmpty()) {
            return found;
        }
        jdbc.sql("""
                SELECT a.uuid AS agent_id, pe.name_full
                FROM agent a
                JOIN person pe ON pe.uuid = a.person_id
                WHERE a.uuid IN (:ids)""")
                .param("ids", wanted)
                .query((rs, rowNum) -> new AgentName(rs.getObject("agent_id", UUID.class), rs.getString("name_full")))
                .list()
                .stream()
                .filter(agent -> agent.nameFull() != null)
                .forEach(agent -> found.put(agent.agentId(), agent.nameFull()));
        return found;
    }
}

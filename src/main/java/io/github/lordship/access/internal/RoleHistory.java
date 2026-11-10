package io.github.lordship.access.internal;

import io.github.lordship.audit.AuditService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The history Glade's Roles screens draw their bars from.
 *
 * <p>A permission given to a role, or a role given to a person, is its own row (role_permission,
 * granted_role), and each grant gets a new row, so those rows cannot carry a screen's history.
 * So every such change is also written as an update of the role and of the person, with one map
 * entry per thing that changed:
 * <ul>
 *   <li>agent_role: {"permissions": {"lots:edit": true}} and {"holders": {"Maria Lopez": true}}</li>
 *   <li>agent: {"roles": {"&lt;roleId&gt;": true}}</li>
 *   <li>agent: {"permissions": {"lots:edit": false}}, a permission taken from the person alone (false) or given back
 *       (true), the way the switch on their page reads (DeniedPermissionService)</li>
 * </ul>
 * The history reads these the way it reads a property's custom fields ("permissions.lots:edit").
 * The role_permission and granted_role rows are still written to the audit log as before.
 */
@Component
public class RoleHistory {

    private final AuditService auditService;
    private final JdbcClient jdbc;

    public RoleHistory(AuditService auditService, JdbcClient jdbc) {
        this.auditService = auditService;
        this.jdbc = jdbc;
    }

    // Permissions given to a role (true) or taken from it (false), as one entry in its history
    public void permissions(UUID roleId, Map<String, Boolean> changed) {
        if (changed.isEmpty()) return;
        auditService.recordUpdate("agent_role", roleId, Map.of("permissions", flipped(changed)), Map.of("permissions", changed));
    }

    // A permission taken from a person alone (has false) or given back (true), as one entry in the person's history.
    // Only the person's own switch: a permission lost or gained with a role is the role's entry, not this.
    public void personPermission(UUID agentId, String permissionName, boolean has) {
        Map<String, Boolean> changed = Map.of(permissionName, has);
        auditService.recordUpdate("agent", agentId, Map.of("permissions", flipped(changed)), Map.of("permissions", changed));
    }

    // A role given to a person (holds true) or taken from them: on the role, and on the person
    public void holder(UUID roleId, UUID agentId, boolean holds) {
        holders(roleId, Map.of(agentId, holds));
    }

    // People given a role or losing it: one entry on the role, and one on each person
    public void holders(UUID roleId, Map<UUID, Boolean> changed) {
        if (changed.isEmpty()) return;
        Map<String, Boolean> byName = byName(changed);
        auditService.recordUpdate("agent_role", roleId, Map.of("holders", flipped(byName)), Map.of("holders", byName));
        holdersOnPeople(roleId, changed);
    }

    // Only the people's side: the role is being deleted (its own delete says enough) or restored (restored() says it)
    public void holdersOnPeople(UUID roleId, Map<UUID, Boolean> changed) {
        changed.forEach((agentId, holds) -> auditService.recordUpdate("agent", agentId,
                Map.of("roles", Map.of(roleId.toString(), !holds)),
                Map.of("roles", Map.of(roleId.toString(), holds))));
    }

    /**
     * A restore, as one entry on the role: deletedAt from the time back to empty, and the people given it back.
     * One entry, so its history does not read "saved with 1 other role" about itself.
     */
    public void restored(UUID roleId, Object deletedAt, List<UUID> givenBack) {
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        before.put("deletedAt", deletedAt);
        after.put("deletedAt", null);
        if (!givenBack.isEmpty()) {
            Map<UUID, Boolean> given = new LinkedHashMap<>();
            givenBack.forEach(agentId -> given.put(agentId, true));
            Map<String, Boolean> byName = byName(given);
            before.put("holders", flipped(byName));
            after.put("holders", byName);
        }
        auditService.recordUpdate("agent_role", roleId, before, after);
    }

    private Map<String, Boolean> byName(Map<UUID, Boolean> changed) {
        Map<String, Boolean> byName = new LinkedHashMap<>();
        changed.forEach((agentId, holds) -> byName.put(nameOf(agentId), holds));
        return byName;
    }

    // The values before the change: every one the other way
    private static <K> Map<K, Boolean> flipped(Map<K, Boolean> changed) {
        Map<K, Boolean> before = new LinkedHashMap<>();
        changed.forEach((key, value) -> before.put(key, !value));
        return before;
    }

    // The person's full name, as the role's history shows it. Their id when they have no name.
    private String nameOf(UUID agentId) {
        return jdbc.sql("""
                SELECT p.name_full
                FROM agent a
                JOIN person p ON p.uuid = a.person_id
                WHERE a.uuid = :agentId
                """)
                .param("agentId", agentId)
                .query(String.class)
                .optional()
                .filter(name -> !name.isBlank())
                .orElse(agentId.toString());
    }
}

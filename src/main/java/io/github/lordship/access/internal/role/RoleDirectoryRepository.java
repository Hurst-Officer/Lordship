package io.github.lordship.access.internal.role;

import io.github.lordship.shared.SystemPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only joins for Glade's Roles screens (RoleDirectory). Writes go through the role services.
 *
 * <p>Every query leaves out deleted roles, revoked grants and permissions, removed agents, and the
 * SYSTEM agent, which is not a person. Names sort case-insensitively.
 */
@Repository
public class RoleDirectoryRepository {

    private final JdbcClient jdbc;

    public RoleDirectoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // One live role, how many people hold it, and how many permissions it has
    public record RoleCountRow(UUID id, String roleName, String roleDescription, int people, int permissions) { }

    public record PersonRow(UUID agentId, String nameFull, String title, String workEmail) { }

    // Every permission, marked with whether the role has it
    public record PermissionMarkRow(UUID permissionId, String permissionName, String groupKey, boolean granted) { }

    // Every live role, marked with whether the person holds it
    public record HeldRoleRow(UUID roleId, String roleName, String roleDescription, boolean held) { }

    // One permission the person has, through one of their roles. A permission held through two roles is two rows.
    // groupOrder is the group's place in the role editor (permission_group.sort_order), so every screen numbers it the same.
    // permissionOrder is the permission's place inside its group (permission.sort_order).
    public record ThroughRow(String permissionName, String groupKey, int groupOrder, int permissionOrder, String roleName) { }

    public record PropertyRow(UUID propertyId, String propertyCode, String propertyName) { }

    public List<RoleCountRow> findRoles() {
        return jdbc.sql("""
                SELECT r.uuid AS id, r.role_name, r.role_description,
                       (SELECT count(DISTINCT gr.agent_id)::int
                          FROM granted_role gr
                          JOIN agent a ON a.uuid = gr.agent_id AND a.deleted_at IS NULL AND a.uuid <> :system
                         WHERE gr.role_id = r.uuid AND gr.deleted_at IS NULL) AS people,
                       (SELECT count(*)::int
                          FROM role_permission rp
                         WHERE rp.role_id = r.uuid AND rp.deleted_at IS NULL) AS permissions
                FROM agent_role r
                WHERE r.deleted_at IS NULL
                ORDER BY lower(r.role_name), r.uuid
                """)
                .param("system", SystemPrincipal.AGENT_UUID)
                .query(RoleCountRow.class)
                .list();
    }

    public List<PersonRow> findPeopleWithoutRole() {
        return jdbc.sql("""
                SELECT a.uuid AS agent_id, p.name_full, a.title, a.work_email
                FROM agent a
                JOIN person p ON p.uuid = a.person_id
                WHERE a.deleted_at IS NULL
                AND a.uuid <> :system
                AND NOT EXISTS (
                    SELECT 1
                    FROM granted_role gr
                    JOIN agent_role r ON r.uuid = gr.role_id
                    WHERE gr.agent_id = a.uuid
                    AND gr.deleted_at IS NULL
                    AND r.deleted_at IS NULL
                )
                ORDER BY lower(p.name_full) NULLS LAST, a.uuid
                """)
                .param("system", SystemPrincipal.AGENT_UUID)
                .query(PersonRow.class)
                .list();
    }

    // Sorted by group, then by position inside the group
    public List<PermissionMarkRow> findPermissionMarks(UUID roleId) {
        return jdbc.sql("""
                SELECT p.uuid AS permission_id, p.permission_name, g.group_key,
                       (rp.uuid IS NOT NULL) AS granted
                FROM permission p
                JOIN permission_group g ON g.uuid = p.group_id
                LEFT JOIN role_permission rp ON rp.permission_id = p.uuid
                                            AND rp.role_id = :roleId
                                            AND rp.deleted_at IS NULL
                ORDER BY g.sort_order, p.sort_order
                """)
                .param("roleId", roleId)
                .query(PermissionMarkRow.class)
                .list();
    }

    public List<PersonRow> findHolders(UUID roleId) {
        return jdbc.sql("""
                SELECT DISTINCT ON (lower(p.name_full), a.uuid)
                       a.uuid AS agent_id, p.name_full, a.title, a.work_email
                FROM granted_role gr
                JOIN agent a ON a.uuid = gr.agent_id
                JOIN person p ON p.uuid = a.person_id
                WHERE gr.role_id = :roleId
                AND gr.deleted_at IS NULL
                AND a.deleted_at IS NULL
                AND a.uuid <> :system
                ORDER BY lower(p.name_full), a.uuid
                """)
                .param("roleId", roleId)
                .param("system", SystemPrincipal.AGENT_UUID)
                .query(PersonRow.class)
                .list();
    }

    public Optional<PersonRow> findPerson(UUID agentId) {
        return jdbc.sql("""
                SELECT a.uuid AS agent_id, p.name_full, a.title, a.work_email
                FROM agent a
                JOIN person p ON p.uuid = a.person_id
                WHERE a.uuid = :agentId
                AND a.deleted_at IS NULL
                AND a.uuid <> :system
                """)
                .param("agentId", agentId)
                .param("system", SystemPrincipal.AGENT_UUID)
                .query(PersonRow.class)
                .optional();
    }

    public List<HeldRoleRow> findRolesMarkedFor(UUID agentId) {
        return jdbc.sql("""
                SELECT r.uuid AS role_id, r.role_name, r.role_description,
                       EXISTS (
                           SELECT 1 FROM granted_role gr
                           WHERE gr.role_id = r.uuid
                           AND gr.agent_id = :agentId
                           AND gr.deleted_at IS NULL
                       ) AS held
                FROM agent_role r
                WHERE r.deleted_at IS NULL
                ORDER BY lower(r.role_name), r.uuid
                """)
                .param("agentId", agentId)
                .query(HeldRoleRow.class)
                .list();
    }

    // What the person can do, and through which roles. Denied permissions are left out,
    // the same as the lookup every request is checked with (PermissionRepository).
    public List<ThroughRow> findPermissionsThroughRoles(UUID agentId) {
        return throughRoles(agentId, false);
    }

    // The permissions the person's roles give but that are denied to the person, and through which roles
    public List<ThroughRow> findDeniedThroughRoles(UUID agentId) {
        return throughRoles(agentId, true);
    }

    // denied false: the permissions the person has. denied true: the ones taken away from the person.
    private List<ThroughRow> throughRoles(UUID agentId, boolean denied) {
        return jdbc.sql("""
                SELECT p.permission_name, g.group_key, g.sort_order AS group_order, p.sort_order AS permission_order, r.role_name
                FROM granted_role gr
                JOIN agent_role r ON r.uuid = gr.role_id AND r.deleted_at IS NULL
                JOIN role_permission rp ON rp.role_id = r.uuid AND rp.deleted_at IS NULL
                JOIN permission p ON p.uuid = rp.permission_id
                JOIN permission_group g ON g.uuid = p.group_id
                WHERE gr.agent_id = :agentId
                AND gr.deleted_at IS NULL
                AND EXISTS (
                    SELECT 1 FROM denied_permission d
                    WHERE d.agent_id = :agentId
                    AND d.permission_id = p.uuid
                    AND d.deleted_at IS NULL
                ) = :denied
                ORDER BY g.sort_order, p.sort_order, lower(r.role_name)
                """)
                .param("agentId", agentId)
                .param("denied", denied)
                .query(ThroughRow.class)
                .list();
    }

    // Assignments to properties that still exist
    public List<PropertyRow> findAssignedProperties(UUID agentId) {
        return jdbc.sql("""
                SELECT pr.uuid AS property_id, pr.property_code, pr.property_name
                FROM agent_property_assignment apa
                JOIN property pr ON pr.uuid = apa.property_id
                WHERE apa.agent_id = :agentId
                AND apa.removed_at IS NULL
                AND pr.deleted_at IS NULL
                ORDER BY lower(pr.property_name) NULLS LAST, pr.uuid
                """)
                .param("agentId", agentId)
                .query(PropertyRow.class)
                .list();
    }
}

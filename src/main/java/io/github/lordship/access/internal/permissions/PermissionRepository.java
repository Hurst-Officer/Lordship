package io.github.lordship.access.internal.permissions;

import io.github.lordship.access.GroupedPermission;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class PermissionRepository {

    private final JdbcClient jdbc;

    public PermissionRepository(JdbcClient jdbcClient) {
        this.jdbc = jdbcClient;
    }

    // permission has no deleted_at column (V3) -- the predicate that used to be
    // here would have thrown the first time anything called this.
    public Optional<PermissionRow> findById(UUID id) {
        return jdbc.sql("""
                SELECT * FROM permission WHERE uuid = :uuid
                """)
                .param("uuid", id)
                .query(PermissionRow.class)
                .optional();
    }

    public Optional<PermissionRow> findByName(String permissionName) {
        return jdbc.sql("""
                SELECT * FROM permission
                WHERE permission_name = :permissionName
                """)
                .param("permissionName", permissionName)
                .query(PermissionRow.class)
                .optional();
    }

    // Every permission, in the order the role editor shows them.
    // Permissions can't be deleted, so there is no deleted_at to check.
    public List<GroupedPermission> getAllPermissions() {
        return jdbc.sql("""
                SELECT p.uuid, p.permission_name, g.group_key
                FROM permission p
                JOIN permission_group g ON g.uuid = p.group_id
                ORDER BY g.sort_order, p.sort_order
                """)
                .query(GroupedPermission.class)
                .list();
    }

    // The agent_role join is the backstop for RoleService.deleteRole: without it a
    // grant outlives the role it names and keeps handing out its permissions.
    public Set<PermissionRow> findActivePermissionsForAgent(UUID agentId) {
        return new HashSet<>(jdbc.sql("""
                SELECT DISTINCT p.*
                FROM permission p
                JOIN role_permission rp ON rp.permission_id = p.uuid
                JOIN granted_role gr ON gr.role_id = rp.role_id
                JOIN agent_role r ON r.uuid = gr.role_id
                WHERE gr.agent_id = :agentId
                 AND gr.deleted_at IS NULL
                 AND rp.deleted_at IS NULL
                 AND r.deleted_at IS NULL
                 AND p.uuid NOT IN (
                 SELECT permission_id
                 FROM denied_permission
                 WHERE agent_id = :agentId
                 AND deleted_at IS NULL
                )
                """).param("agentId", agentId)
                .query(PermissionRow.class)
                .list());
    }

}
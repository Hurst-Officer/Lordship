package io.github.lordship.access.internal.deniedpermission;

import io.github.lordship.access.DeniedPermission;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes and reads denied_permission. The denials are honored by PermissionRepository.findActivePermissionsForAgent
 * (every request's check), AccessKeys.anyoneHolds and RoleDirectoryRepository, not here.
 */
@Repository
public class DeniedPermissionRepository {

    private final JdbcClient jdbc;

    public DeniedPermissionRepository(JdbcClient jdbcClient) {
        this.jdbc = jdbcClient;
    }

    public DeniedPermissionRow save(DeniedPermissionRow row) {
        return jdbc.sql("""
                INSERT INTO denied_permission (agent_id, permission_id, denied_by)
                VALUES (:agentId, :permissionId, :deniedBy)
                RETURNING *
                """)
                .paramSource(row)
                .query(DeniedPermissionRow.class)
                .single();
    }

    // The denial in force for this agent and permission, if there is one
    public Optional<DeniedPermissionRow> findActive(UUID agentId, UUID permissionId) {
        return jdbc.sql("""
                SELECT * FROM denied_permission
                WHERE agent_id = :agentId
                AND permission_id = :permissionId
                AND deleted_at IS NULL
                """)
                .param("agentId", agentId)
                .param("permissionId", permissionId)
                .query(DeniedPermissionRow.class)
                .optional();
    }

    // Every denial in force for the agent, oldest first, with the permission's name
    public List<DeniedPermission> findActiveForAgent(UUID agentId) {
        return jdbc.sql("""
                SELECT d.uuid, d.agent_id, d.permission_id, p.permission_name, d.denied_by, d.created_at
                FROM denied_permission d
                JOIN permission p ON p.uuid = d.permission_id
                WHERE d.agent_id = :agentId
                AND d.deleted_at IS NULL
                ORDER BY d.created_at, d.uuid
                """)
                .param("agentId", agentId)
                .query(DeniedPermission.class)
                .list();
    }

    // True when a live role the agent holds gives the permission. Denials are not looked at here.
    public boolean rolesGive(UUID agentId, UUID permissionId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM granted_role gr
                    JOIN agent_role r ON r.uuid = gr.role_id AND r.deleted_at IS NULL
                    JOIN role_permission rp ON rp.role_id = r.uuid AND rp.deleted_at IS NULL
                    WHERE gr.agent_id = :agentId
                    AND gr.deleted_at IS NULL
                    AND rp.permission_id = :permissionId
                )
                """)
                .param("agentId", agentId)
                .param("permissionId", permissionId)
                .query(Boolean.class)
                .single();
    }

    // Soft delete that also records who gave the permission back.
    // False when the denial does not exist or was already lifted.
    public boolean remove(UUID uuid, UUID removedBy) {
        return jdbc.sql("""
                UPDATE denied_permission
                SET deleted_at = now(),
                    denial_removed_by = :removedBy
                WHERE uuid = :uuid
                AND deleted_at IS NULL
                """)
                .param("uuid", uuid)
                .param("removedBy", removedBy)
                .update() > 0;
    }
}

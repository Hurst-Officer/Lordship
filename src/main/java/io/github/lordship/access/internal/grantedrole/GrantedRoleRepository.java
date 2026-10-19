package io.github.lordship.access.internal.grantedrole;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class GrantedRoleRepository {

    private final JdbcClient jdbc;

    public GrantedRoleRepository(JdbcClient jdbcClient) {
        this.jdbc = jdbcClient;
    }

    public GrantedRoleRow save(GrantedRoleRow row) {
        return jdbc.sql("""
                INSERT INTO granted_role (agent_id, role_id, granted_by)
                VALUES (:agentId, :roleId, :grantedBy)
                RETURNING *
                """)
                .paramSource(row)
                .query(GrantedRoleRow.class)
                .single();
    }

    public Optional<GrantedRoleRow> findById(UUID uuid) {
        return jdbc.sql("""
                SELECT * FROM granted_role
                WHERE uuid = :uuid
                """)
                .param("uuid", uuid)
                .query(GrantedRoleRow.class)
                .optional();
    }

    public List<GrantedRoleRow> findByAgentId(UUID agentId) {
        return jdbc.sql("""
                SELECT * FROM granted_role
                WHERE agent_id = :agentId
                AND deleted_at IS NULL
                """)
                .param("agentId", agentId)
                .query(GrantedRoleRow.class)
                .list();
    }

    // skips revoked grants and deleted roles, the same as the permission lookup
    public List<String> findActiveRoleNamesForAgent(UUID agentId) {
        return jdbc.sql("""
                SELECT r.role_name
                FROM granted_role gr
                JOIN agent_role r ON r.uuid = gr.role_id
                WHERE gr.agent_id = :agentId
                AND gr.deleted_at IS NULL
                AND r.deleted_at IS NULL
                ORDER BY r.role_name
                """)
                .param("agentId", agentId)
                .query(String.class)
                .list();
    }

    public List<GrantedRoleRow> findByRoleId(UUID roleId) {
        return jdbc.sql("""
                SELECT * FROM granted_role
                WHERE role_id = :roleId
                AND deleted_at IS NULL
                """)
                .param("roleId", roleId)
                .query(GrantedRoleRow.class)
                .list();
    }

    public Optional<GrantedRoleRow> findActiveGrant(UUID agentId, UUID roleId) {
        return jdbc.sql("""
                SELECT * FROM granted_role
                WHERE agent_id = :agentId
                AND role_id = :roleId
                AND deleted_at IS NULL
                """)
                .param("agentId", agentId)
                .param("roleId", roleId)
                .query(GrantedRoleRow.class)
                .optional();
    }

    // soft delete that also records who did the revoking.
    // returns false when the grant does not exist or was already revoked
    public boolean revoke(UUID uuid, UUID revokedBy) {
        return jdbc.sql("""
                UPDATE granted_role
                SET deleted_at = now(),
                    revoked_by = :revokedBy
                WHERE uuid = :uuid
                AND deleted_at IS NULL
                """)
                .param("uuid", uuid)
                .param("revokedBy", revokedBy)
                .update() > 0;
    }
}

package io.github.lordship.personnel.internal;

import io.github.lordship.personnel.internal.PersonnelRows.AgentRoleRow;
import io.github.lordship.personnel.internal.PersonnelRows.AgentRow;
import io.github.lordship.personnel.internal.PersonnelRows.AssignedPropertyRow;
import io.github.lordship.personnel.internal.PersonnelRows.PermissionMarkRow;
import io.github.lordship.personnel.internal.PersonnelRows.RoleMemberRow;
import io.github.lordship.personnel.internal.PersonnelRows.RoleRow;
import io.github.lordship.shared.SystemPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only joins for the Personnel screens. Writes go through the owning modules.
 *
 * <p>Every query here leaves out soft-deleted agents, roles and grants, and the
 * SYSTEM agent, which is not a person. Names sort case-insensitively.
 */
@Repository
public class PersonnelRepository {

    private final JdbcClient jdbc;

    public PersonnelRepository(JdbcClient jdbcClient) {
        this.jdbc = jdbcClient;
    }

    // every live role, with one row per holder. A role nobody holds still gets one row
    public List<RoleMemberRow> findRolesWithMembers() {
        return jdbc.sql("""
                SELECT r.uuid AS role_id, r.role_name, r.role_description,
                       a.uuid AS agent_id, p.name_full
                FROM agent_role r
                LEFT JOIN granted_role gr ON gr.role_id = r.uuid AND gr.deleted_at IS NULL
                LEFT JOIN agent a ON a.uuid = gr.agent_id
                                 AND a.deleted_at IS NULL
                                 AND a.uuid <> :system
                LEFT JOIN person p ON p.uuid = a.person_id
                WHERE r.deleted_at IS NULL
                ORDER BY lower(r.role_name), r.uuid, lower(p.name_full) NULLS LAST, a.uuid
                """)
                .param("system", SystemPrincipal.AGENT_UUID)
                .query(RoleMemberRow.class)
                .list();
    }

    public List<AgentRow> findAgentsWithoutRole() {
        return jdbc.sql("""
                SELECT a.uuid AS agent_id, p.name_full, a.work_email, a.work_phone
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
                .query(AgentRow.class)
                .list();
    }

    public Optional<RoleRow> findRole(UUID roleId) {
        return jdbc.sql("""
                SELECT uuid AS role_id, role_name, role_description
                FROM agent_role
                WHERE uuid = :roleId AND deleted_at IS NULL
                """)
                .param("roleId", roleId)
                .query(RoleRow.class)
                .optional();
    }

    public List<AgentRow> findRoleMembers(UUID roleId) {
        return jdbc.sql("""
                SELECT a.uuid AS agent_id, p.name_full, a.work_email, a.work_phone
                FROM granted_role gr
                JOIN agent a ON a.uuid = gr.agent_id
                JOIN person p ON p.uuid = a.person_id
                WHERE gr.role_id = :roleId
                AND gr.deleted_at IS NULL
                AND a.deleted_at IS NULL
                AND a.uuid <> :system
                ORDER BY lower(p.name_full) NULLS LAST, a.uuid
                """)
                .param("roleId", roleId)
                .param("system", SystemPrincipal.AGENT_UUID)
                .query(AgentRow.class)
                .list();
    }

    // every permission in the system, marked with whether this role holds it.
    // Sorted by group, then by position inside the group.
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

    public Optional<AgentRow> findAgent(UUID agentId) {
        return jdbc.sql("""
                SELECT a.uuid AS agent_id, p.name_full, a.work_email, a.work_phone
                FROM agent a
                JOIN person p ON p.uuid = a.person_id
                WHERE a.uuid = :agentId
                AND a.deleted_at IS NULL
                AND a.uuid <> :system
                """)
                .param("agentId", agentId)
                .param("system", SystemPrincipal.AGENT_UUID)
                .query(AgentRow.class)
                .optional();
    }

    // the roles of several agents in one trip, so a list does not cost a query per agent
    public List<AgentRoleRow> findRolesForAgents(Collection<UUID> agentIds) {
        if (agentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT gr.agent_id, r.uuid AS role_id, r.role_name
                FROM granted_role gr
                JOIN agent_role r ON r.uuid = gr.role_id
                WHERE gr.agent_id IN (:agentIds)
                AND gr.deleted_at IS NULL
                AND r.deleted_at IS NULL
                ORDER BY lower(r.role_name), r.uuid
                """)
                .param("agentIds", agentIds)
                .query(AgentRoleRow.class)
                .list();
    }

    // assignments to properties that still exist
    public List<AssignedPropertyRow> findAssignedProperties(UUID agentId) {
        return jdbc.sql("""
                SELECT apa.uuid AS assignment_id, pr.uuid AS property_id,
                       pr.property_code, pr.property_name
                FROM agent_property_assignment apa
                JOIN property pr ON pr.uuid = apa.property_id
                WHERE apa.agent_id = :agentId
                AND apa.removed_at IS NULL
                AND pr.deleted_at IS NULL
                ORDER BY lower(pr.property_name) NULLS LAST, pr.uuid
                """)
                .param("agentId", agentId)
                .query(AssignedPropertyRow.class)
                .list();
    }

    // containsText is matched literally: % and _ in it are not wildcards
    public List<AgentRow> searchByName(String containsText, int limit) {
        String escaped = containsText
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");

        return jdbc.sql("""
                SELECT a.uuid AS agent_id, p.name_full, a.work_email, a.work_phone
                FROM agent a
                JOIN person p ON p.uuid = a.person_id
                WHERE a.deleted_at IS NULL
                AND a.uuid <> :system
                AND p.name_full ILIKE ('%' || :text || '%') ESCAPE '\\'
                ORDER BY lower(p.name_full), a.uuid
                LIMIT :limit
                """)
                .param("system", SystemPrincipal.AGENT_UUID)
                .param("text", escaped)
                .param("limit", limit)
                .query(AgentRow.class)
                .list();
    }

    public boolean propertyExists(UUID propertyId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM property WHERE uuid = :propertyId AND deleted_at IS NULL
                )
                """)
                .param("propertyId", propertyId)
                .query(Boolean.class)
                .single();
    }

    public List<AgentRow> findAgentsAssignedToProperty(UUID propertyId) {
        return jdbc.sql("""
                SELECT a.uuid AS agent_id, p.name_full, a.work_email, a.work_phone
                FROM agent_property_assignment apa
                JOIN agent a ON a.uuid = apa.agent_id
                JOIN person p ON p.uuid = a.person_id
                WHERE apa.property_id = :propertyId
                AND apa.removed_at IS NULL
                AND a.deleted_at IS NULL
                AND a.uuid <> :system
                ORDER BY lower(p.name_full) NULLS LAST, a.uuid
                """)
                .param("propertyId", propertyId)
                .param("system", SystemPrincipal.AGENT_UUID)
                .query(AgentRow.class)
                .list();
    }
}

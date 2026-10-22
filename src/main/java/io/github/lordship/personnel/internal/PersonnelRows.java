package io.github.lordship.personnel.internal;

import java.util.UUID;

// Rows read by PersonnelRepository. Each one is one line of a JOIN, not a table.
final class PersonnelRows {

    private PersonnelRows() { }

    record AgentRow(
            UUID agentId,
            String nameFull,
            String title,
            String workEmail,
            String workPhone
    ) { }

    record RoleRow(
            UUID roleId,
            String roleName,
            String roleDescription
    ) { }

    // one role and one of its holders; agentId is null for a role nobody holds
    record RoleMemberRow(
            UUID roleId,
            String roleName,
            String roleDescription,
            UUID agentId,
            String nameFull
    ) { }

    record AgentRoleRow(
            UUID agentId,
            UUID roleId,
            String roleName
    ) { }

    record PermissionMarkRow(
            UUID permissionId,
            String permissionName,
            String groupKey,
            boolean granted
    ) { }

    record AssignedPropertyRow(
            UUID assignmentId,
            UUID propertyId,
            String propertyCode,
            String propertyName
    ) { }
}

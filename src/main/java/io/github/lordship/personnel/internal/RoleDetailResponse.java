package io.github.lordship.personnel.internal;

import java.util.List;
import java.util.UUID;

// GET /api/personnel/roles/{roleId} -- one role, its holders, and every permission marked
public record RoleDetailResponse(
        UUID roleId,
        String roleName,
        String roleDescription,
        List<Member> agents,
        List<PermissionMark> permissions
) {
    public record Member(
            UUID agentId,
            String nameFull,
            String workEmail,
            String workPhone
    ) { }

    // label, description and groupLabel are in the language of the Accept-Language header
    public record PermissionMark(
            UUID permissionId,
            String permissionName,
            boolean granted,
            String label,
            String description,
            String groupKey,
            String groupLabel
    ) { }
}

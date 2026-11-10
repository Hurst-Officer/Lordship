package io.github.lordship.access.internal.deniedpermission;

import io.github.lordship.access.DeniedPermission;

import java.time.OffsetDateTime;
import java.util.UUID;

// One row of denied_permission (V3). deletedAt and denialRemovedBy are set when the permission is given back.
public record DeniedPermissionRow(
        UUID uuid,
        UUID agentId,
        UUID permissionId,
        UUID deniedBy,
        UUID denialRemovedBy,
        OffsetDateTime createdAt,
        OffsetDateTime deletedAt
) {
    public DeniedPermissionRow(UUID agentId, UUID permissionId, UUID deniedBy) {
        this(null, agentId, permissionId, deniedBy, null, null, null);
    }

    public DeniedPermission toDeniedPermission(String permissionName) {
        return new DeniedPermission(uuid, agentId, permissionId, permissionName, deniedBy, createdAt);
    }
}

package io.github.lordship.access.internal.rbac;

import java.time.Instant;
import java.util.UUID;

public record RolePermissionResponse(
        UUID id,
        UUID roleId,
        UUID permissionId,
        Instant createdAt,
        Instant deletedAt
) { }
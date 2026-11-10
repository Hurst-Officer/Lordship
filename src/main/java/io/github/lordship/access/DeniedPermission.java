package io.github.lordship.access;

import java.time.OffsetDateTime;
import java.util.UUID;

// One permission taken away from one agent, even though a role of the agent's gives that permission
public record DeniedPermission(
        UUID uuid,
        UUID agentId,
        UUID permissionId,
        String permissionName,
        UUID deniedBy,
        OffsetDateTime createdAt
) { }

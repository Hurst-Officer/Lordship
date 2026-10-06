package io.github.lordship.access.internal.role;

import java.util.List;
import java.util.UUID;

public record RoleResponse (
        UUID id,
        String roleName,
        String roleDescription,
        List<String> permissions
)
{ }

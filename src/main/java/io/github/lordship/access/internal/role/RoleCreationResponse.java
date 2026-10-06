package io.github.lordship.access.internal.role;

import io.github.lordship.access.Role;

import java.util.UUID;

public record RoleCreationResponse(UUID id,
                                   String roleName,
                                   String roleDescription) {

    public static RoleCreationResponse from(Role role) {
        return new RoleCreationResponse(
                role.uuid(),
                role.roleName(),
                role.roleDescription()
        );
    }
}

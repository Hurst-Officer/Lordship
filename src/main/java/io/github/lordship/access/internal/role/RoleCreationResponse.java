package io.github.lordship.access.internal.role;

import io.github.lordship.access.Role;
import io.github.lordship.access.RoleService;

import java.util.UUID;

// builtIn: the server finds this role by name, so it keeps its name and cannot be deleted (RoleService.BUILT_IN)
public record RoleCreationResponse(UUID id,
                                   String roleName,
                                   String roleDescription,
                                   boolean builtIn) {

    public static RoleCreationResponse from(Role role) {
        return new RoleCreationResponse(
                role.uuid(),
                role.roleName(),
                role.roleDescription(),
                RoleService.isBuiltIn(role.roleName())
        );
    }
}

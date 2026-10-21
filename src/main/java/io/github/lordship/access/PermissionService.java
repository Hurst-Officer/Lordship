package io.github.lordship.access;

import io.github.lordship.access.internal.permissions.PermissionRepository;
import io.github.lordship.access.internal.permissions.PermissionRow;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PermissionService {

    private final PermissionRepository permissionRepository;

    public PermissionService(PermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    // note that the scope is narrow - the permissionService WILL NOT make sure the agent exists ect
    public Set<Permission> findPermissionsForAgent(UUID agentId) {
        return permissionRepository.findActivePermissionsForAgent(agentId)
                .stream()
                .map(PermissionRow::toPermission)
                .collect(Collectors.toUnmodifiableSet());
    }

    // Sorted by group, then by position inside the group.
    public List<GroupedPermission> getAllPermissions() {
        return permissionRepository.getAllPermissions();
    }
}

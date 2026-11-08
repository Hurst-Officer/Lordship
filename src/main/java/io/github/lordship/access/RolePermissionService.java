package io.github.lordship.access;

import io.github.lordship.access.internal.AccessKeys;
import io.github.lordship.access.internal.RoleHistory;
import io.github.lordship.access.internal.permissions.PermissionRepository;
import io.github.lordship.access.internal.permissions.PermissionRow;
import io.github.lordship.access.internal.rbac.RolePermissionRepository;
import io.github.lordship.access.internal.rbac.RolePermissionRow;
import io.github.lordship.access.internal.role.RoleRepository;
import io.github.lordship.audit.AuditMapper;
import io.github.lordship.audit.AuditService;
import io.github.lordship.identity.AgentAuthorizationCache;
import io.github.lordship.shared.InvalidRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Gives permissions to roles and takes them away. Every change takes effect on the next request:
// the authorization cache is dropped, and each holder's permissions are read again.
@Service
public class RolePermissionService {

    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;
    private final AuditService auditService;
    private final AgentAuthorizationCache authorizationCache;
    private final AccessKeys accessKeys;
    private final RoleHistory roleHistory;

    public RolePermissionService(RolePermissionRepository rolePermissionRepository,
                                 PermissionRepository permissionRepository,
                                 RoleRepository roleRepository,
                                 AuditService auditService,
                                 AgentAuthorizationCache authorizationCache,
                                 AccessKeys accessKeys,
                                 RoleHistory roleHistory) {
        this.rolePermissionRepository = rolePermissionRepository;
        this.permissionRepository = permissionRepository;
        this.roleRepository = roleRepository;
        this.auditService = auditService;
        this.authorizationCache = authorizationCache;
        this.accessKeys = accessKeys;
        this.roleHistory = roleHistory;
    }

    // Giving a permission the role already has is a no-op: the existing grant comes back, nothing is audited.
    @Transactional
    public RolePermission appendPermission(UUID roleId, UUID permissionId) {
        return append(roleId, permissionId, true);
    }

    // history false: the caller writes one history entry for several permissions (RoleHistory.permissions)
    private RolePermission append(UUID roleId, UUID permissionId, boolean history) {
        Optional<RolePermissionRow> existing = rolePermissionRepository.findActiveGrant(roleId, permissionId);
        if (existing.isPresent()) {
            return existing.get().toRolePermission();
        }
        PermissionRow permission = permissionRepository.findById(permissionId)
                .orElseThrow(() -> InvalidRequest.onField("permissionId", "permission.unknown", permissionId));
        requireLiveRole(roleId);

        RolePermissionRow row = rolePermissionRepository.save(
                new RolePermissionRow(roleId, permissionId)
        );
        authorizationCache.invalidateAll();
        auditService.recordInsert("role_permission", row.uuid(), AuditMapper.toMap(row));
        if (history) {
            roleHistory.permissions(roleId, Map.of(permission.permissionName(), true));
        }
        return row.toRolePermission();
    }

    @Transactional
    public RolePermission appendPermissionByName(UUID roleId, String permissionName) {
        return appendPermission(roleId, permissionNamed(permissionName).uuid());
    }

    /**
     * Takes a permission from a role. Refused (409 access.last_key) when it would leave nobody
     * who can manage roles (see AccessKeys).
     */
    @Transactional
    public boolean revokeById(UUID rolePermissionId) {
        return accessKeys.keep(() -> revoke(rolePermissionId, true));
    }

    // history false: the caller writes one history entry for several permissions (RoleHistory.permissions)
    private boolean revoke(UUID rolePermissionId, boolean history) {
        Optional<RolePermissionRow> before = rolePermissionRepository.findById(rolePermissionId);
        if (before.isEmpty()) return false;
        // a deleted role keeps its permissions for a restore; they are not changed while it is deleted
        requireLiveRole(before.get().roleId());

        if (!rolePermissionRepository.revoke(rolePermissionId)) return false;

        authorizationCache.invalidateAll();
        auditService.recordDelete("role_permission", rolePermissionId, AuditMapper.toMap(before.get()));
        if (history) {
            permissionRepository.findById(before.get().permissionId()).ifPresent(permission ->
                    roleHistory.permissions(before.get().roleId(), Map.of(permission.permissionName(), false)));
        }
        return true;
    }

    @Transactional
    public boolean revokePermission(UUID roleId, UUID permissionId) {
        return rolePermissionRepository.findActiveGrant(roleId, permissionId)
                .map(row -> revokeById(row.uuid()))
                .orElse(false);
    }

    @Transactional
    public boolean revokePermissionByName(UUID roleId, String permissionName) {
        return revokePermission(roleId, permissionNamed(permissionName).uuid());
    }

    /**
     * Gives (granted true) or takes away several permissions of one role at once, like a whole group
     * in Glade's role editor. All or nothing: an unknown name (400 permission.unknown) or the last keys
     * (409 access.last_key) changes none of them. Permissions already that way are skipped.
     * Returns the names that actually changed, so an undo can put back exactly those.
     * Empty when the role is unknown or deleted.
     */
    @Transactional
    public Optional<List<String>> setPermissions(UUID roleId, List<String> permissionNames, boolean granted) {
        if (roleRepository.findById(roleId).isEmpty()) {
            return Optional.empty();
        }
        List<PermissionRow> permissions = new ArrayList<>();
        for (String name : new LinkedHashSet<>(permissionNames == null ? List.<String>of() : permissionNames)) {
            permissions.add(permissionNamed(name));
        }
        return Optional.of(accessKeys.keep(() -> {
            Map<String, Boolean> changed = new LinkedHashMap<>();
            for (PermissionRow permission : permissions) {
                Optional<RolePermissionRow> grant = rolePermissionRepository.findActiveGrant(roleId, permission.uuid());
                if (grant.isPresent() == granted) continue;
                if (granted) {
                    append(roleId, permission.uuid(), false);
                } else {
                    revoke(grant.get().uuid(), false);
                }
                changed.put(permission.permissionName(), granted);
            }
            // one entry in the role's history for the whole change
            if (!changed.isEmpty()) {
                roleHistory.permissions(roleId, changed);
            }
            return List.copyOf(changed.keySet());
        }));
    }

    // Gives the new role every permission the other one has. For a role made from another.
    @Transactional
    public int copyPermissions(UUID fromRoleId, UUID toRoleId) {
        Map<String, Boolean> copied = new LinkedHashMap<>();
        for (RolePermissionRow row : rolePermissionRepository.findByRoleId(fromRoleId)) {
            if (rolePermissionRepository.findActiveGrant(toRoleId, row.permissionId()).isEmpty()) {
                append(toRoleId, row.permissionId(), false);
                permissionRepository.findById(row.permissionId())
                        .ifPresent(permission -> copied.put(permission.permissionName(), true));
            }
        }
        if (!copied.isEmpty()) {
            roleHistory.permissions(toRoleId, copied);
        }
        return copied.size();
    }

    // returns revoked rows too, so the caller can tell "never granted" from "revoked"
    public Optional<RolePermission> findById(UUID rolePermissionId) {
        return rolePermissionRepository.findById(rolePermissionId).map(RolePermissionRow::toRolePermission);
    }

    // Empty for an unknown or deleted role
    public Optional<List<RolePermission>> findLivePermissionsForRole(UUID roleId) {
        if (roleRepository.findById(roleId).isEmpty()) return Optional.empty();
        return Optional.of(findPermissionsForRole(roleId));
    }

    public List<RolePermission> findPermissionsForRole(UUID roleId) {
        return rolePermissionRepository.findByRoleId(roleId)
                .stream()
                .map(RolePermissionRow::toRolePermission)
                .toList();
    }

    // the reverse lookup: every role currently carrying a given permission
    public List<RolePermission> findRolesWithPermission(UUID permissionId) {
        return rolePermissionRepository.findByPermissionId(permissionId)
                .stream()
                .map(RolePermissionRow::toRolePermission)
                .toList();
    }

    private PermissionRow permissionNamed(String permissionName) {
        return permissionRepository.findByName(permissionName)
                .orElseThrow(() -> InvalidRequest.onField("permissionName", "permission.unknown", permissionName));
    }

    // A permission can only be given to a live role. 400 role.unknown on roleId otherwise.
    private void requireLiveRole(UUID roleId) {
        if (roleRepository.findById(roleId).isEmpty()) {
            throw InvalidRequest.onField("roleId", "role.unknown", roleId);
        }
    }
}

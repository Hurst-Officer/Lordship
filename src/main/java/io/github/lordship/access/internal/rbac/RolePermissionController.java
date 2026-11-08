package io.github.lordship.access.internal.rbac;

import io.github.lordship.access.RolePermission;
import io.github.lordship.access.RolePermissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/role-permissions")
public class RolePermissionController {

    public record RolePermissionRequest(
            @NotNull
            UUID roleId,

            @NotBlank
            String permissionName
    ) { }

    // Several permissions of one role at once: given (granted true) or taken away
    public record SetPermissionsRequest(
            @NotNull
            UUID roleId,

            @NotNull
            List<String> permissionNames,

            // required: a body without it is a 400, never read as "take them away"
            @NotNull
            Boolean granted
    ) { }

    // updated: how many changed. changed: their names (those already that way are left out)
    public record SetPermissionsResponse(int updated, List<String> changed) { }

    private final RolePermissionService rolePermissionService;

    public RolePermissionController(RolePermissionService rolePermissionService) {
        this.rolePermissionService = rolePermissionService;
    }

    @PreAuthorize("hasAuthority('role_permissions:edit')")
    @PostMapping("/append")
    public ResponseEntity<RolePermissionResponse> appendPermission(@Valid @RequestBody RolePermissionRequest rolePermissionRequest) {
        RolePermission rolePermission = rolePermissionService.appendPermissionByName(
                rolePermissionRequest.roleId(),
                rolePermissionRequest.permissionName());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(rolePermission.toResponse());
    }

    /**
     * Gives or takes several permissions of one role at once, like a whole group in Glade's role editor.
     * Giving needs role_permissions:edit, taking needs role_permissions:delete (403 otherwise).
     * All or nothing. Answers { updated, changed }: how many changed and which (those already that way are skipped).
     * 404 for an unknown or deleted role. 400 permission.unknown. 409 access.last_key.
     */
    @PreAuthorize("hasAuthority('role_permissions:edit') or hasAuthority('role_permissions:delete')")
    @PostMapping("/set")
    public ResponseEntity<SetPermissionsResponse> setPermissions(@Valid @RequestBody SetPermissionsRequest request,
                                                                 Authentication authentication) {
        String needed = request.granted() ? "role_permissions:edit" : "role_permissions:delete";
        boolean allowed = authentication.getAuthorities().stream()
                .anyMatch(authority -> needed.equals(authority.getAuthority()));
        if (!allowed) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return rolePermissionService.setPermissions(request.roleId(), request.permissionNames(), request.granted())
                .map(changed -> ResponseEntity.ok(new SetPermissionsResponse(changed.size(), changed)))
                .orElse(ResponseEntity.notFound().build());
    }

    // the mirror image of /append - same body, so the client does not have to
    // look up the role_permission id just to take a permission away
    @PreAuthorize("hasAuthority('role_permissions:delete')")
    @PostMapping("/revoke")
    public ResponseEntity<Void> revokePermission(@Valid @RequestBody RolePermissionRequest rolePermissionRequest) {
        boolean revoked = rolePermissionService.revokePermissionByName(
                rolePermissionRequest.roleId(),
                rolePermissionRequest.permissionName());
        return revoked
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PreAuthorize("hasAuthority('role_permissions:delete')")
    @DeleteMapping("/{rolePermissionId}")
    public ResponseEntity<Void> revokeById(@PathVariable UUID rolePermissionId) {
        boolean revoked = rolePermissionService.revokeById(rolePermissionId);
        return revoked
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PreAuthorize("hasAuthority('role_permissions:view')")
    @GetMapping("/{rolePermissionId}")
    public ResponseEntity<RolePermissionResponse> findById(@PathVariable UUID rolePermissionId) {
        return rolePermissionService.findById(rolePermissionId)
                .map(RolePermission::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // 404 for an unknown or deleted role
    @PreAuthorize("hasAuthority('role_permissions:view')")
    @GetMapping("/role/{roleId}")
    public ResponseEntity<List<RolePermissionResponse>> findPermissionsForRole(@PathVariable UUID roleId) {
        return rolePermissionService.findLivePermissionsForRole(roleId)
                .map(found -> ResponseEntity.ok(found.stream().map(RolePermission::toResponse).toList()))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('role_permissions:view')")
    @GetMapping("/permission/{permissionId}")
    public ResponseEntity<List<RolePermissionResponse>> findRolesWithPermission(@PathVariable UUID permissionId) {
        List<RolePermissionResponse> roles = rolePermissionService.findRolesWithPermission(permissionId)
                .stream()
                .map(RolePermission::toResponse)
                .toList();
        return ResponseEntity.ok(roles);
    }
}
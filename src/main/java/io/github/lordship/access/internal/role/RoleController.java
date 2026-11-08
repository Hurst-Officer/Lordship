package io.github.lordship.access.internal.role;

import io.github.lordship.access.Role;
import io.github.lordship.access.RoleService;
import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditService;
import io.github.lordship.audit.History;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/roles")
public class RoleController {

    // copyFromRoleId: a live role whose permissions the new one starts with. Leave it out to start with none.
    // The name is checked by RoleService, so a refusal comes back in the worker's language.
    public record RoleCreationRequest(
            String roleName,

            String roleDescription,

            UUID copyFromRoleId
    ) { }

    // One deleted role. deletedBy is null without audit:view.
    public record DeletedRoleResponse(
            UUID id,
            String roleName,
            String roleDescription,
            OffsetDateTime deletedAt,
            String deletedBy
    ) { }

    private final RoleService roleService;
    private final AuditContext auditContext;
    private final AuditService auditService;

    public RoleController(RoleService roleService,
                          AuditContext auditContext,
                          AuditService auditService) {
        this.roleService = roleService;
        this.auditContext = auditContext;
        this.auditService = auditService;
    }

    // 400 role.name_blank, role.name_too_long or role.unknown (copyFromRoleId); 409 role.name_taken.
    // Copying another role's permissions gives permissions, so it also needs role_permissions:edit (403 otherwise).
    @PreAuthorize("hasAuthority('agent_roles:create')")
    @PostMapping
    public ResponseEntity<RoleCreationResponse> createNewRole(@Valid @RequestBody RoleCreationRequest roleCreationRequest,
                                                              Authentication authentication) {
        if (roleCreationRequest.copyFromRoleId() != null && !can(authentication, "role_permissions:edit")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Role role = roleService.createRole(
                roleCreationRequest.roleName(),
                roleCreationRequest.roleDescription(),
                roleCreationRequest.copyFromRoleId());
        RoleCreationResponse roleCreationResponse = RoleCreationResponse.from(role);
        return ResponseEntity.status(HttpStatus.CREATED).body(roleCreationResponse);
    }

    // Deleted roles, the most recently deleted first
    @PreAuthorize("hasAuthority('agent_roles:delete')")
    @GetMapping("/deleted")
    public ResponseEntity<List<DeletedRoleResponse>> getDeletedRoles(Authentication authentication) {
        List<Role> roles = roleService.findDeleted();
        boolean canViewAudits = can(authentication, "audit:view");
        Map<UUID, History.Mark> who = canViewAudits
                ? auditService.deletions("agent_role", roles.stream().map(Role::uuid).toList())
                : Map.of();
        return ResponseEntity.ok(roles.stream()
                .map(role -> {
                    History.Mark mark = who.get(role.uuid());
                    return new DeletedRoleResponse(role.uuid(), role.roleName(), role.roleDescription(),
                            role.deletedAt(), mark == null ? null : mark.name());
                })
                .toList());
    }

    @PreAuthorize("hasAuthority('agent_roles:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<RoleCreationResponse> getRole(@PathVariable UUID uuid) {
        return roleService.findById(uuid).map(role -> ResponseEntity.ok().body(RoleCreationResponse.from(role)))
                .orElse(ResponseEntity.notFound().build());
    }

    // 404 for an unknown or already deleted role. 409 role.built_in, or access.last_key.
    @PreAuthorize("hasAuthority('agent_roles:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteRole(@PathVariable UUID uuid) {
        return roleService.deleteRole(uuid, ActingAgent.resolve(auditContext)) ?
                ResponseEntity.noContent().build() :
                ResponseEntity.notFound().build();
    }

    // Brings a deleted role back, with its permissions and the people who lost it when it was deleted.
    // Giving it back to people needs agent_roles:edit too. A live role is answered as it is.
    // 404 for an unknown role. 409 role.name_taken.
    @PreAuthorize("hasAuthority('agent_roles:delete') and hasAuthority('agent_roles:edit')")
    @PostMapping("/{uuid}/restore")
    public ResponseEntity<RoleCreationResponse> restoreRole(@PathVariable UUID uuid) {
        return roleService.restoreRole(uuid)
                .map(RoleCreationResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // 400 role.name_blank or role.name_too_long; 409 role.name_taken, or role.built_in for a new name on a built-in role
    @PreAuthorize("hasAuthority('agent_roles:edit')")
    @PatchMapping("/{uuid}")
    public ResponseEntity<RoleCreationResponse> patchRole(@PathVariable UUID uuid, @RequestBody Map<String, Object> request) {
        Map<String, Object> changes = new HashMap<>();

        if (request.containsKey("roleName")) changes.put("role_name", request.get("roleName"));
        if (request.containsKey("roleDescription")) changes.put("role_description", request.get("roleDescription"));

        return roleService.patchRole(uuid, changes)
                .map(RoleCreationResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private static boolean can(Authentication authentication, String permission) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> permission.equals(authority.getAuthority()));
    }
}

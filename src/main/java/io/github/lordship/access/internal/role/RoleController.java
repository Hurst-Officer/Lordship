package io.github.lordship.access.internal.role;

import io.github.lordship.access.Role;
import io.github.lordship.access.RoleService;
import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/api/roles")
public class RoleController {

    public record RoleCreationRequest(
            @NotBlank
            @Size(max = 60)
            String roleName,

            String roleDescription
    ) { }

    private final RoleService roleService;
    private final AuditContext auditContext;

    public RoleController(RoleService roleService,
                          AuditContext auditContext) {
        this.roleService = roleService;
        this.auditContext = auditContext;
    }

    @PreAuthorize("hasAuthority('agent_roles:create')")
    @PostMapping
    public ResponseEntity<RoleCreationResponse> createNewRole(@Valid @RequestBody RoleCreationRequest roleCreationRequest) {
        Role role = roleService.createRole(roleCreationRequest.roleName(), roleCreationRequest.roleDescription());
        RoleCreationResponse roleCreationResponse = RoleCreationResponse.from(role);
        return ResponseEntity.status(HttpStatus.CREATED).body(roleCreationResponse);
    }

    @PreAuthorize("hasAuthority('agent_roles:view')")
    @GetMapping("/{uuid}")
    public ResponseEntity<RoleCreationResponse> getRole(@PathVariable UUID uuid) {
        return roleService.findById(uuid).map(role -> ResponseEntity.ok().body(RoleCreationResponse.from(role)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('agent_roles:delete')")
    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> deleteRole(@PathVariable UUID uuid) {
        return roleService.deleteRole(uuid, ActingAgent.resolve(auditContext)) ?
                ResponseEntity.noContent().build() :
                ResponseEntity.notFound().build();
    }


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
}
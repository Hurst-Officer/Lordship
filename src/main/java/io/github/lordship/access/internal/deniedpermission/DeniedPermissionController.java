package io.github.lordship.access.internal.deniedpermission;

import io.github.lordship.access.DeniedPermission;
import io.github.lordship.access.DeniedPermissionService;
import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Takes one permission away from one agent, on top of the agent's roles, and gives the permission back.
 * Uses the same permissions as giving and taking roles: taking away needs agent_roles:delete,
 * giving back needs agent_roles:edit.
 */
@RestController
@RequestMapping("/api/denied-permissions")
public class DeniedPermissionController {

    public record DeniedPermissionRequest(
            @NotNull
            UUID agentId,

            @NotBlank
            String permissionName
    ) { }

    public record DeniedPermissionResponse(
            UUID id,
            UUID agentId,
            UUID permissionId,
            String permissionName,
            UUID deniedBy,
            OffsetDateTime createdAt
    ) {
        static DeniedPermissionResponse from(DeniedPermission denied) {
            return new DeniedPermissionResponse(denied.uuid(), denied.agentId(), denied.permissionId(),
                    denied.permissionName(), denied.deniedBy(), denied.createdAt());
        }
    }

    private final DeniedPermissionService deniedPermissionService;
    private final AuditContext auditContext;

    public DeniedPermissionController(DeniedPermissionService deniedPermissionService,
                                      AuditContext auditContext) {
        this.deniedPermissionService = deniedPermissionService;
        this.auditContext = auditContext;
    }

    /**
     * 201 with the denial. Denying a permission already denied answers the existing denial.
     * 404 for an unknown or removed agent. 400 permission.unknown.
     * 400 permission.not_held when none of the agent's roles gives the permission.
     * 409 access.last_key when nobody could manage roles afterwards.
     */
    @PreAuthorize("hasAuthority('agent_roles:delete')")
    @PostMapping("/deny")
    public ResponseEntity<DeniedPermissionResponse> deny(@Valid @RequestBody DeniedPermissionRequest request) {
        return deniedPermissionService.deny(request.agentId(), request.permissionName(), ActingAgent.resolve(auditContext))
                .map(denied -> ResponseEntity.status(HttpStatus.CREATED).body(DeniedPermissionResponse.from(denied)))
                .orElse(ResponseEntity.notFound().build());
    }

    // The mirror image of /deny, same body. 204, or 404 when the permission is not denied to the agent.
    // 400 permission.unknown.
    @PreAuthorize("hasAuthority('agent_roles:edit')")
    @PostMapping("/restore")
    public ResponseEntity<Void> restore(@Valid @RequestBody DeniedPermissionRequest request) {
        return deniedPermissionService.restore(request.agentId(), request.permissionName(), ActingAgent.resolve(auditContext))
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // Every denial in force for the agent, oldest first. 404 for an unknown or removed agent.
    @PreAuthorize("hasAuthority('agent_roles:view')")
    @GetMapping("/agent/{agentId}")
    public ResponseEntity<List<DeniedPermissionResponse>> findForAgent(@PathVariable UUID agentId) {
        return deniedPermissionService.findForAgent(agentId)
                .map(found -> ResponseEntity.ok(found.stream().map(DeniedPermissionResponse::from).toList()))
                .orElse(ResponseEntity.notFound().build());
    }
}

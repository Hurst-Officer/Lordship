package io.github.lordship.access.internal.permissions;

import io.github.lordship.access.PermissionService;
import io.github.lordship.access.PermissionText;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    // label, description and groupLabel are in the language of the Accept-Language header
    public record PermissionResponse(
            UUID id,
            String permissionName,
            String label,
            String description,
            String groupKey,
            String groupLabel
    ) { }

    private final PermissionService permissionService;
    private final PermissionText permissionText;

    public PermissionController(PermissionService permissionService,
                                PermissionText permissionText) {
        this.permissionService = permissionService;
        this.permissionText = permissionText;
    }

    // Sorted by group, then by position inside the group.
    @PreAuthorize("hasAuthority('permissions:view')")
    @GetMapping
    public ResponseEntity<List<PermissionResponse>> getAllPermissions() {
        return ResponseEntity.ok(
                permissionService.getAllPermissions().stream()
                        .map(p -> new PermissionResponse(
                                p.uuid(),
                                p.permissionName(),
                                permissionText.label(p.permissionName()),
                                permissionText.description(p.permissionName()),
                                p.groupKey(),
                                permissionText.groupLabel(p.groupKey())))
                        .toList()
        );
    }
}

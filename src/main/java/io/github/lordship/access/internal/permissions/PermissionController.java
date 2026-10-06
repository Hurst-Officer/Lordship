package io.github.lordship.access.internal.permissions;

import io.github.lordship.access.PermissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/api/permissions")
public class PermissionController {

    public record PermissionResponse(
            UUID id,
            String permissionName
    ) { }

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @PreAuthorize("hasAuthority('permissions:view')")
    @GetMapping
    public ResponseEntity<List<PermissionResponse>> getAllPermissions() {
        return ResponseEntity.ok(
                permissionService.getAllPermissions().stream()
                        .map(p -> new PermissionResponse(p.uuid(), p.permissionName()))
                        .toList()
        );
    }
}
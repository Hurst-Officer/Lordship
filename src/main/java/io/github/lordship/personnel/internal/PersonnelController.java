package io.github.lordship.personnel.internal;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * One read call per Personnel screen in Glade. Edits use the existing small endpoints
 * (granted-roles, role-permissions, property-assignments, agents).
 * Every list is sorted case-insensitively by name.
 */
@RestController
@RequestMapping("/api/personnel")
public class PersonnelController {

    private final PersonnelService personnelService;

    public PersonnelController(PersonnelService personnelService) {
        this.personnelService = personnelService;
    }

    // Global view: every role with its people, plus the people who hold no role.
    @PreAuthorize("hasAuthority('agents:view')")
    @GetMapping
    public ResponseEntity<PersonnelResponse> overview() {
        return ResponseEntity.ok(personnelService.overview());
    }

    // Role view: holders, and every permission marked granted or not. 404 for an unknown role.
    @PreAuthorize("hasAuthority('role_permissions:view') and hasAuthority('permissions:view')")
    @GetMapping("/roles/{roleId}")
    public ResponseEntity<RoleDetailResponse> role(@PathVariable UUID roleId) {
        return personnelService.role(roleId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Agent view. effectivePermissions is in the body only when the caller holds
     * role_permissions:view; otherwise the field is left out, not an error.
     * 404 for an unknown or removed agent.
     */
    @PreAuthorize("hasAuthority('agents:view')")
    @GetMapping("/agents/{agentId}")
    public ResponseEntity<AgentDetailResponse> agent(@PathVariable UUID agentId, Authentication authentication) {
        boolean canSeePermissions = authentication.getAuthorities()
                .stream()
                .anyMatch(a -> a.getAuthority().equals("role_permissions:view"));

        return personnelService.agent(agentId, canSeePermissions)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Name search: case-insensitive "contains", at most 10. A blank q returns [].
    @PreAuthorize("hasAuthority('agents:view')")
    @GetMapping("/search")
    public ResponseEntity<List<AgentSummaryResponse>> search(@RequestParam(defaultValue = "") String q) {
        return ResponseEntity.ok(personnelService.search(q));
    }

    // Property view: the agents assigned to one property. 404 for an unknown property.
    @PreAuthorize("hasAuthority('agents:view')")
    @GetMapping("/property/{propertyId}")
    public ResponseEntity<List<PropertyAgentResponse>> propertyAgents(@PathVariable UUID propertyId) {
        return personnelService.propertyAgents(propertyId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}

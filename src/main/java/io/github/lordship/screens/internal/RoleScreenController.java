package io.github.lordship.screens.internal;

import io.github.lordship.access.Role;
import io.github.lordship.access.RoleDirectory;
import io.github.lordship.access.RoleDirectory.EffectivePermission;
import io.github.lordship.access.RoleDirectory.PersonLine;
import io.github.lordship.access.RoleService;
import io.github.lordship.audit.AuditService;
import io.github.lordship.audit.History;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One call per screen of Glade's Roles concern: every role, one role, one person.
 * Each part needs the permission its own endpoint needs; without it that part is null.
 * Roles are not tied to a property, so no PropertyScope applies here.
 * zone is the viewer's time zone, like America/Los_Angeles. The history's days follow it.
 */
@RestController
@RequestMapping("/api/screens")
public class RoleScreenController {

    // The permission that gives an agent every property (PropertyScope.All)
    private static final String ALL_PROPERTIES = "assignments:assign-all";

    private final RoleService roleService;
    private final RoleDirectory roleDirectory;
    private final AuditService auditService;

    public RoleScreenController(RoleService roleService, RoleDirectory roleDirectory, AuditService auditService) {
        this.roleService = roleService;
        this.roleDirectory = roleDirectory;
        this.auditService = auditService;
    }

    // The list of roles: every live role with how many people hold it and how many permissions it has,
    // and the people who hold no role.
    @PreAuthorize("hasAuthority('agent_roles:view')")
    @GetMapping("/roles")
    public ResponseEntity<ScreenResponses.RolesScreen> roles() {
        return ResponseEntity.ok(new ScreenResponses.RolesScreen(roleDirectory.roles(), roleDirectory.peopleWithoutRole()));
    }

    // The role page: the role, every permission marked, the people holding it, and its history.
    // 404 for an unknown or deleted role. permissions is null without role_permissions:view.
    @PreAuthorize("hasAuthority('agent_roles:view')")
    @GetMapping("/role/{roleId}")
    public ResponseEntity<ScreenResponses.RoleScreen> role(@PathVariable UUID roleId,
                                                           @RequestParam(required = false) String zone,
                                                           Authentication authentication) {
        Optional<Role> found = roleService.findById(roleId);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Set<String> can = authorities(authentication);
        Role role = found.get();
        History.PageHistory history = can.contains("audit:view")
                ? auditService.pageHistory(List.of(new History.Ref("agent_role", roleId)), List.of(), AuditService.zoneOf(zone))
                : null;
        // what the role can do is not shown without role_permissions:view, so neither is its history
        if (history != null && !can.contains("role_permissions:view")) {
            history = withoutPermissions(history);
        }
        return ResponseEntity.ok(new ScreenResponses.RoleScreen(
                ScreenResponses.RoleRecord.from(role),
                can.contains("role_permissions:view") ? roleDirectory.permissionMarks(roleId) : null,
                roleDirectory.holders(roleId),
                history));
    }

    // The person page: who they are, every live role marked with whether they hold it, what that lets them do
    // (role_permissions:view), the properties they work with (assignments:view), and their history.
    // 404 for an unknown or removed agent, and for the server's own agent.
    @PreAuthorize("hasAuthority('agent_roles:view')")
    @GetMapping("/person/{agentId}")
    public ResponseEntity<ScreenResponses.PersonScreen> person(@PathVariable UUID agentId,
                                                               @RequestParam(required = false) String zone,
                                                               Authentication authentication) {
        Optional<PersonLine> found = roleDirectory.person(agentId);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Set<String> can = authorities(authentication);
        List<EffectivePermission> permissions = roleDirectory.permissionsOf(agentId);
        boolean allProperties = permissions.stream().anyMatch(p -> ALL_PROPERTIES.equals(p.permissionName()));
        History.PageHistory history = can.contains("audit:view")
                ? auditService.pageHistory(List.of(new History.Ref("agent", agentId)), List.of(), AuditService.zoneOf(zone))
                : null;
        return ResponseEntity.ok(new ScreenResponses.PersonScreen(
                found.get(),
                roleDirectory.rolesOf(agentId),
                can.contains("role_permissions:view") ? permissions : null,
                allProperties,
                can.contains("assignments:view") ? roleDirectory.propertiesOf(agentId) : null,
                history));
    }

    // The history without the role's "permissions" entries (their keys name permissions)
    private static History.PageHistory withoutPermissions(History.PageHistory history) {
        return new History.PageHistory(history.lastEdit(), history.records().stream()
                .map(r -> new History.RecordHistory(r.table(), r.recordId(), r.added(), r.fields().entrySet().stream()
                        .filter(e -> !e.getKey().equals("permissions") && !e.getKey().startsWith("permissions."))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))))
                .toList());
    }

    // The permissions this request was checked with
    private static Set<String> authorities(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}

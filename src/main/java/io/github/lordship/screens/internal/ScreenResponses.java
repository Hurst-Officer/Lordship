package io.github.lordship.screens.internal;

import io.github.lordship.access.Role;
import io.github.lordship.access.RoleDirectory;
import io.github.lordship.access.RoleService;
import io.github.lordship.access.internal.agents.AgentOwnResponse;
import io.github.lordship.audit.History;
import io.github.lordship.homes.internal.HomeResponse;
import io.github.lordship.lots.internal.LotResponse;
import io.github.lordship.persons.internal.PersonOwnResponse;
import io.github.lordship.properties.internal.PropertyResponse;

import java.util.List;
import java.util.UUID;

// What GET /api/screens/... returns: everything one Glade screen shows, except the map layers.
public final class ScreenResponses {

    private ScreenResponses() { }

    // The property page (X2).
    // lots is null without lots:view. history is null without audit:view.
    public record PropertyScreen(
            PropertyResponse property,
            List<LotResponse> lots,
            History.PageHistory history
    ) { }

    // ---- Roles ----

    // A role, in the same shape as GET /api/roles/{id}, so the role page can save through that endpoint.
    // builtIn: the server finds this role by name, so it keeps its name and cannot be deleted.
    public record RoleRecord(UUID id, String roleName, String roleDescription, boolean builtIn) {
        static RoleRecord from(Role role) {
            return new RoleRecord(role.uuid(), role.roleName(), role.roleDescription(), RoleService.isBuiltIn(role.roleName()));
        }
    }

    // The list of roles (X1): every live role, and the people who hold none
    public record RolesScreen(
            List<RoleDirectory.RoleLine> roles,
            List<RoleDirectory.PersonLine> noRole
    ) { }

    // The role page (X2). permissions is null without role_permissions:view. history is null without audit:view.
    public record RoleScreen(
            RoleRecord role,
            List<RoleDirectory.PermissionMark> permissions,
            List<RoleDirectory.PersonLine> holders,
            History.PageHistory history
    ) { }

    // The person page (X3). permissions is null without role_permissions:view; properties null without
    // assignments:view. allProperties: their roles give them every property. history is null without audit:view.
    public record PersonScreen(
            RoleDirectory.PersonLine person,
            List<RoleDirectory.HeldRole> roles,
            List<RoleDirectory.EffectivePermission> permissions,
            boolean allProperties,
            List<RoleDirectory.AssignedProperty> properties,
            History.PageHistory history
    ) { }

    // The lot page (X3).
    // property is the lot's property, for the page's header. homes is null without homes:view.
    // history is null without audit:view.
    public record LotScreen(
            LotResponse lot,
            PropertyResponse property,
            List<HomeResponse> homes,
            History.PageHistory history
    ) { }

    // The signed-in agent's own page. agentId and personId are always there, for presence and the history bars.
    // agent is null without agents:view_own, person without persons:view_own,
    // history without audit:view_own (or audit:view).
    public record SelfScreen(
            java.util.UUID agentId,
            java.util.UUID personId,
            AgentOwnResponse agent,
            PersonOwnResponse person,
            History.PageHistory history
    ) { }
}

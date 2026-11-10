package io.github.lordship.access;

import io.github.lordship.access.internal.role.RoleDirectoryRepository;
import io.github.lordship.access.internal.role.RoleDirectoryRepository.ThroughRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What Glade's Roles screens show: every role, one role (what it can do, who holds it),
 * and one person (which roles they hold, what that lets them do). Read-only.
 * Permission and group names come back in the language of the request (PermissionText).
 */
@Service
public class RoleDirectory {

    // One live role, for the list of roles. builtIn: see RoleService.BUILT_IN.
    public record RoleLine(UUID id, String roleName, String roleDescription, boolean builtIn, int people, int permissions) { }

    // A person who signs in to Glade
    public record PersonLine(UUID agentId, String nameFull, String title, String workEmail) { }

    // One permission, marked with whether the role has it
    public record PermissionMark(UUID permissionId, String permissionName, String label, String description,
                                 String groupKey, String groupLabel, boolean granted) { }

    // One live role, marked with whether the person holds it
    public record HeldRole(UUID roleId, String roleName, String roleDescription, boolean builtIn, boolean held) { }

    // One permission the person has, and the roles it comes through.
    // groupOrder is the group's number in the role editor (1 Staff and access, 2 People...).
    // permissionOrder is the permission's place inside its group, so a screen showing permissionsOf and deniedOf
    // together can keep each permission where the role editor puts it.
    public record EffectivePermission(String permissionName, String label, String groupKey, String groupLabel,
                                      int groupOrder, int permissionOrder, List<String> through) { }

    // A property the person is assigned to
    public record AssignedProperty(UUID propertyId, String propertyCode, String propertyName) { }

    private final RoleDirectoryRepository repository;
    private final PermissionText permissionText;

    public RoleDirectory(RoleDirectoryRepository repository, PermissionText permissionText) {
        this.repository = repository;
        this.permissionText = permissionText;
    }

    // Every live role, by name
    public List<RoleLine> roles() {
        return repository.findRoles().stream()
                .map(r -> new RoleLine(r.id(), r.roleName(), r.roleDescription(), RoleService.isBuiltIn(r.roleName()),
                        r.people(), r.permissions()))
                .toList();
    }

    // People who hold no live role, so they can do nothing in Glade
    public List<PersonLine> peopleWithoutRole() {
        return repository.findPeopleWithoutRole().stream().map(RoleDirectory::line).toList();
    }

    // Every permission marked for one role, by group and then by its place in the group
    public List<PermissionMark> permissionMarks(UUID roleId) {
        return repository.findPermissionMarks(roleId).stream()
                .map(p -> new PermissionMark(
                        p.permissionId(),
                        p.permissionName(),
                        permissionText.label(p.permissionName()),
                        permissionText.description(p.permissionName()),
                        p.groupKey(),
                        permissionText.groupLabel(p.groupKey()),
                        p.granted()))
                .toList();
    }

    // The people holding one role, by name
    public List<PersonLine> holders(UUID roleId) {
        return repository.findHolders(roleId).stream().map(RoleDirectory::line).toList();
    }

    // A live person, not the server
    public Optional<PersonLine> person(UUID agentId) {
        return repository.findPerson(agentId).map(RoleDirectory::line);
    }

    // Every live role, marked with whether this person holds it
    public List<HeldRole> rolesOf(UUID agentId) {
        return repository.findRolesMarkedFor(agentId).stream()
                .map(r -> new HeldRole(r.roleId(), r.roleName(), r.roleDescription(), RoleService.isBuiltIn(r.roleName()), r.held()))
                .toList();
    }

    // What the person can do, each permission with the roles it comes through, by group
    public List<EffectivePermission> permissionsOf(UUID agentId) {
        return grouped(repository.findPermissionsThroughRoles(agentId));
    }

    // The permissions the person's roles give but that are taken away from the person (DeniedPermissionService),
    // in the same shape as permissionsOf. A denial whose permission no role of theirs gives any more is not listed.
    public List<EffectivePermission> deniedOf(UUID agentId) {
        return grouped(repository.findDeniedThroughRoles(agentId));
    }

    // One EffectivePermission per permission name, with every role the permission comes through
    private List<EffectivePermission> grouped(List<ThroughRow> rows) {
        Map<String, List<String>> through = new LinkedHashMap<>();
        Map<String, ThroughRow> first = new LinkedHashMap<>();
        for (ThroughRow row : rows) {
            through.computeIfAbsent(row.permissionName(), name -> new ArrayList<>()).add(row.roleName());
            first.putIfAbsent(row.permissionName(), row);
        }
        List<EffectivePermission> permissions = new ArrayList<>();
        through.forEach((name, roles) -> permissions.add(new EffectivePermission(
                name,
                permissionText.label(name),
                first.get(name).groupKey(),
                permissionText.groupLabel(first.get(name).groupKey()),
                first.get(name).groupOrder(),
                first.get(name).permissionOrder(),
                List.copyOf(roles))));
        return permissions;
    }

    // The properties the person is assigned to (edited in Personnel)
    public List<AssignedProperty> propertiesOf(UUID agentId) {
        return repository.findAssignedProperties(agentId).stream()
                .map(p -> new AssignedProperty(p.propertyId(), p.propertyCode(), p.propertyName()))
                .toList();
    }

    private static PersonLine line(RoleDirectoryRepository.PersonRow row) {
        return new PersonLine(row.agentId(), row.nameFull(), row.title(), row.workEmail());
    }
}

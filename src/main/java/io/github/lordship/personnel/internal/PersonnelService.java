package io.github.lordship.personnel.internal;

import io.github.lordship.access.Permission;
import io.github.lordship.access.PermissionService;
import io.github.lordship.access.PermissionText;
import io.github.lordship.personnel.internal.AgentDetailResponse.PropertyRef;
import io.github.lordship.personnel.internal.AgentDetailResponse.RoleRef;
import io.github.lordship.personnel.internal.PersonnelResponse.AgentName;
import io.github.lordship.personnel.internal.PersonnelResponse.RoleGroup;
import io.github.lordship.personnel.internal.PersonnelRows.AgentRoleRow;
import io.github.lordship.personnel.internal.PersonnelRows.AgentRow;
import io.github.lordship.personnel.internal.PersonnelRows.RoleMemberRow;
import io.github.lordship.personnel.internal.RoleDetailResponse.Member;
import io.github.lordship.personnel.internal.RoleDetailResponse.PermissionMark;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

// Builds each Personnel screen from one call. Read-only.
@Service
public class PersonnelService {

    static final int SEARCH_LIMIT = 10;

    private final PersonnelRepository personnelRepository;
    private final PermissionService permissionService;
    private final PermissionText permissionText;

    public PersonnelService(PersonnelRepository personnelRepository,
                            PermissionService permissionService,
                            PermissionText permissionText) {
        this.personnelRepository = personnelRepository;
        this.permissionService = permissionService;
        this.permissionText = permissionText;
    }

    public PersonnelResponse overview() {
        // the rows arrive sorted by role, then by name, so grouping keeps that order
        Map<UUID, RoleGroup> groups = new LinkedHashMap<>();
        for (RoleMemberRow row : personnelRepository.findRolesWithMembers()) {
            RoleGroup group = groups.computeIfAbsent(row.roleId(), id -> new RoleGroup(
                    id, row.roleName(), row.roleDescription(), new ArrayList<>()));
            if (row.agentId() != null) {
                group.agents().add(new AgentName(row.agentId(), row.nameFull()));
            }
        }

        List<AgentName> noRole = personnelRepository.findAgentsWithoutRole()
                .stream()
                .map(a -> new AgentName(a.agentId(), a.nameFull()))
                .toList();

        return new PersonnelResponse(List.copyOf(groups.values()), noRole);
    }

    public Optional<RoleDetailResponse> role(UUID roleId) {
        return personnelRepository.findRole(roleId).map(role -> new RoleDetailResponse(
                role.roleId(),
                role.roleName(),
                role.roleDescription(),
                personnelRepository.findRoleMembers(roleId)
                        .stream()
                        .map(a -> new Member(a.agentId(), a.nameFull(), a.workEmail(), a.workPhone()))
                        .toList(),
                personnelRepository.findPermissionMarks(roleId)
                        .stream()
                        .map(p -> new PermissionMark(
                                p.permissionId(),
                                p.permissionName(),
                                p.granted(),
                                permissionText.label(p.permissionName()),
                                permissionText.description(p.permissionName()),
                                p.groupKey(),
                                permissionText.groupLabel(p.groupKey())))
                        .toList()
        ));
    }

    // includePermissions is false when the caller may not see them; the field is then left out
    public Optional<AgentDetailResponse> agent(UUID agentId, boolean includePermissions) {
        return personnelRepository.findAgent(agentId).map(agent -> new AgentDetailResponse(
                agent.agentId(),
                agent.nameFull(),
                agent.title(),
                agent.workEmail(),
                agent.workPhone(),
                personnelRepository.findRolesForAgents(List.of(agentId))
                        .stream()
                        .map(r -> new RoleRef(r.roleId(), r.roleName()))
                        .toList(),
                personnelRepository.findAssignedProperties(agentId)
                        .stream()
                        .map(p -> new PropertyRef(p.assignmentId(), p.propertyId(), p.propertyCode(), p.propertyName()))
                        .toList(),
                includePermissions ? effectivePermissions(agentId) : null
        ));
    }

    public List<AgentSummaryResponse> search(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<AgentRow> agents = personnelRepository.searchByName(text.trim(), SEARCH_LIMIT);
        Map<UUID, List<String>> roleNames = roleNamesByAgent(agents);

        return agents.stream()
                .map(a -> new AgentSummaryResponse(
                        a.agentId(), a.nameFull(), a.title(), roleNames.getOrDefault(a.agentId(), List.of())))
                .toList();
    }

    // empty when the property does not exist, so the controller can answer 404
    public Optional<List<PropertyAgentResponse>> propertyAgents(UUID propertyId) {
        if (!personnelRepository.propertyExists(propertyId)) {
            return Optional.empty();
        }
        List<AgentRow> agents = personnelRepository.findAgentsAssignedToProperty(propertyId);
        Map<UUID, List<String>> roleNames = roleNamesByAgent(agents);

        return Optional.of(agents.stream()
                .map(a -> new PropertyAgentResponse(
                        a.agentId(), a.nameFull(), a.title(), a.workEmail(), a.workPhone(),
                        roleNames.getOrDefault(a.agentId(), List.of())))
                .toList());
    }

    // The same lookup the 403 check uses: it skips revoked grants, deleted roles and denials.
    private List<String> effectivePermissions(UUID agentId) {
        return permissionService.findPermissionsForAgent(agentId)
                .stream()
                .map(Permission::permissionName)
                .sorted()
                .toList();
    }

    // role names keep the repository's sort order within each agent
    private Map<UUID, List<String>> roleNamesByAgent(List<AgentRow> agents) {
        List<UUID> ids = agents.stream().map(AgentRow::agentId).toList();
        return personnelRepository.findRolesForAgents(ids)
                .stream()
                .collect(Collectors.groupingBy(
                        AgentRoleRow::agentId,
                        Collectors.mapping(AgentRoleRow::roleName, Collectors.toList())));
    }
}

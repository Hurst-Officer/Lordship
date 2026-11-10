package io.github.lordship.access.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.access.internal.agents.AgentRepository;
import io.github.lordship.access.internal.agents.AgentRow;
import io.github.lordship.access.internal.deniedpermission.DeniedPermissionRepository;
import io.github.lordship.access.internal.deniedpermission.DeniedPermissionRow;
import io.github.lordship.access.internal.grantedrole.GrantedRoleRepository;
import io.github.lordship.access.internal.grantedrole.GrantedRoleRow;
import io.github.lordship.access.internal.permissions.PermissionRepository;
import io.github.lordship.access.internal.permissions.PermissionRow;
import io.github.lordship.access.internal.rbac.RolePermissionRepository;
import io.github.lordship.access.internal.rbac.RolePermissionRow;
import io.github.lordship.access.internal.role.RoleRepository;
import io.github.lordship.access.internal.role.RoleRow;
import io.github.lordship.persons.internal.PersonRow;
import io.github.lordship.shared.SystemPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixtures go in through repositories rather than services: a service call pulls
 * in the audit write, which has no principal outside an authenticated request.
 */
@Transactional
public class PermissionRepositoryTest extends IntegrationTest {

    @Autowired
    PermissionRepository permissionRepository;

    @Autowired
    RoleRepository roleRepository;

    @Autowired
    RolePermissionRepository rolePermissionRepository;

    @Autowired
    GrantedRoleRepository grantedRoleRepository;

    @Autowired
    AgentRepository agentRepository;

    @Autowired
    DeniedPermissionRepository deniedPermissionRepository;

    private static final String PERMISSION_NAME = "tenants:view";

    private UUID insertAgent() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        PersonRow person = testData.insertPerson("Permission Test " + suffix);
        AgentRow agent = agentRepository.save(
                new AgentRow(person.uuid(), null, "perm-" + suffix + "@example.test", "not-a-real-hash"));
        return agent.uuid();
    }

    private UUID insertRoleHolding(String permissionName) {
        PermissionRow permission = permissionRepository.findByName(permissionName)
                .orElseThrow(() -> new AssertionError("seeded permission missing: " + permissionName));

        RoleRow role = roleRepository.save(
                new RoleRow("Perm Test Role " + UUID.randomUUID().toString().substring(0, 8)));
        rolePermissionRepository.save(new RolePermissionRow(role.uuid(), permission.uuid()));
        return role.uuid();
    }

    private boolean agentHolds(UUID agentId, String permissionName) {
        Set<PermissionRow> permissions = permissionRepository.findActivePermissionsForAgent(agentId);
        return permissions.stream().anyMatch(p -> permissionName.equals(p.permissionName()));
    }

    @Test
    void findActivePermissionsForAgent_shouldDropPermission_whenRoleIsSoftDeleted() {
        // Arrange
        UUID agentId = insertAgent();
        UUID roleId = insertRoleHolding(PERMISSION_NAME);
        grantedRoleRepository.save(new GrantedRoleRow(agentId, roleId, SystemPrincipal.AGENT_UUID));

        assertTrue(agentHolds(agentId, PERMISSION_NAME),
                "fixture is wrong if the agent does not hold the permission to begin with");

        // Act
        assertTrue(roleRepository.softDelete(roleId));

        // Assert
        assertFalse(agentHolds(agentId, PERMISSION_NAME),
                "a soft-deleted role must stop handing out its permissions, grant or no grant");
    }

    @Test
    void findActivePermissionsForAgent_shouldDropPermission_whenGrantIsRevoked() {
        // Arrange
        UUID agentId = insertAgent();
        UUID roleId = insertRoleHolding(PERMISSION_NAME);
        GrantedRoleRow grant = grantedRoleRepository.save(
                new GrantedRoleRow(agentId, roleId, SystemPrincipal.AGENT_UUID));

        assertTrue(agentHolds(agentId, PERMISSION_NAME));

        // Act
        assertTrue(grantedRoleRepository.revoke(grant.uuid(), SystemPrincipal.AGENT_UUID));

        // Assert
        assertFalse(agentHolds(agentId, PERMISSION_NAME));
    }

    @Test
    void findActivePermissionsForAgent_shouldDropPermission_whileDenied() {
        // Arrange
        UUID agentId = insertAgent();
        UUID roleId = insertRoleHolding(PERMISSION_NAME);
        grantedRoleRepository.save(new GrantedRoleRow(agentId, roleId, SystemPrincipal.AGENT_UUID));
        UUID permissionId = permissionRepository.findByName(PERMISSION_NAME).orElseThrow().uuid();

        assertTrue(deniedPermissionRepository.rolesGive(agentId, permissionId),
                "fixture is wrong if the agent's role does not give the permission");

        // Act
        DeniedPermissionRow denial = deniedPermissionRepository.save(
                new DeniedPermissionRow(agentId, permissionId, SystemPrincipal.AGENT_UUID));

        // Assert
        assertFalse(agentHolds(agentId, PERMISSION_NAME),
                "a denied permission must stop being handed out, role or no role");
        assertTrue(deniedPermissionRepository.rolesGive(agentId, permissionId),
                "the role still gives the permission; only the denial takes the permission away");

        // Act: the denial is lifted
        assertTrue(deniedPermissionRepository.remove(denial.uuid(), SystemPrincipal.AGENT_UUID));

        // Assert
        assertTrue(agentHolds(agentId, PERMISSION_NAME));
        assertFalse(deniedPermissionRepository.remove(denial.uuid(), SystemPrincipal.AGENT_UUID),
                "a denial already lifted cannot be lifted twice");
    }

    @Test
    void findActivePermissionsForAgent_shouldReturnNothing_forAnAgentWithNoGrants() {
        // Arrange
        UUID agentId = insertAgent();

        // Act
        Set<PermissionRow> permissions = permissionRepository.findActivePermissionsForAgent(agentId);

        // Assert
        assertTrue(permissions.isEmpty());
    }
}
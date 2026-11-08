package io.github.lordship.access.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.TestAuthSupport.TestAgent;
import io.github.lordship.access.internal.role.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admins edit live roles from Glade's Roles concern. These are the rules that keep that safe:
 * the keys to the role editor stay with someone (AccessKeys), built-in roles keep their names,
 * role names are taken whatever their case, a restore brings a role back whole, and each change
 * needs the permission for what it really does.
 *
 * <p>Every test rolls back. Inside one transaction now() does not move, so a grant revoked earlier in the
 * same test would share a deleted role's deleted_at; no test here revokes a grant before a delete it restores.
 *
 * <p>/api/tenants is incidental, as in RoleDeletionIT: 404 means the request got past the gate, 403 that it did not.
 */
@Transactional
public class RoleEditingIT extends IntegrationTest {

    // the four permissions AccessKeys keeps with someone
    private static final String[] KEYS = {
            "agent_roles:view", "agent_roles:edit", "role_permissions:view", "role_permissions:edit"};

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RoleRepository roleRepository;

    private String rootToken() throws Exception {
        return TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
    }

    private UUID adminRoleId() {
        return roleRepository.findByName("Admin").orElseThrow(() -> new AssertionError("Admin role missing")).uuid();
    }

    private UUID createRole(String token, String roleName) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/roles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleName": "%s" }
                                """.formatted(roleName)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asString());
    }

    private void revokePermission(String token, UUID roleId, String permissionName, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/role-permissions/revoke")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleId": "%s", "permissionName": "%s" }
                                """.formatted(roleId, permissionName)))
                .andExpect(status().is(expectedStatus));
    }

    // ---- the keys -------------------------------------------------------------------------------

    @Test
    void takingTheLastKeysAway_isRefusedOnEveryPath() throws Exception {
        // Arrange: one agent holds the keys through their own role; Admin loses one key, so root no longer has them all
        String rootToken = rootToken();
        TestAgent keeper = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, KEYS);
        revokePermission(rootToken, adminRoleId(), "agent_roles:edit", 204);

        // Act + Assert: a key off the keeper's role
        mockMvc.perform(post("/api/role-permissions/revoke")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleId": "%s", "permissionName": "role_permissions:edit" }
                                """.formatted(keeper.roleId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("access.last_key"));

        // the whole group at once
        mockMvc.perform(post("/api/role-permissions/set")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleId": "%s", "permissionNames": ["agent_roles:view"], "granted": false }
                                """.formatted(keeper.roleId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("access.last_key"));

        // the role taken from the keeper
        mockMvc.perform(post("/api/granted-roles/revoke")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "agentId": "%s", "roleId": "%s" }
                                """.formatted(keeper.uuid(), keeper.roleId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("access.last_key"));

        // the keeper's role deleted
        mockMvc.perform(delete("/api/roles/{uuid}", keeper.roleId())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("access.last_key"));

        // the keeper removed (Personnel)
        mockMvc.perform(delete("/api/agents/{uuid}", keeper.uuid())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("access.last_key"));

        // the keeper still manages roles
        mockMvc.perform(get("/api/roles/{uuid}", keeper.roleId())
                        .header("Authorization", "Bearer " + keeper.token()))
                .andExpect(status().isOk());
    }

    @Test
    void takingKeysFromOneOfTwoHolders_isAllowed() throws Exception {
        // Arrange: root (Admin) and a second agent both hold the keys
        String rootToken = rootToken();
        TestAgent keeper = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, KEYS);

        // Act + Assert
        revokePermission(rootToken, keeper.roleId(), "role_permissions:edit", 204);
    }

    // ---- built-in roles and names --------------------------------------------------------------

    @Test
    void aBuiltInRole_keepsItsName_butItsDescriptionCanChange() throws Exception {
        // Arrange
        String rootToken = rootToken();
        UUID officeStaff = roleRepository.findByName("Office Staff").orElseThrow().uuid();

        // Act + Assert
        mockMvc.perform(patch("/api/roles/{uuid}", officeStaff)
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleName": "Front Office" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("role.built_in"));

        mockMvc.perform(patch("/api/roles/{uuid}", officeStaff)
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleDescription": "Front desk and phones" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleDescription").value("Front desk and phones"))
                .andExpect(jsonPath("$.builtIn").value(true));
    }

    @Test
    void aRoleName_isTakenWhateverItsCase() throws Exception {
        // Arrange
        String rootToken = rootToken();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        createRole(rootToken, "Case Role " + suffix);

        // Act + Assert
        mockMvc.perform(post("/api/roles")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleName": "  CASE ROLE %s  " }
                                """.formatted(suffix.toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("role.name_taken"));

        mockMvc.perform(post("/api/roles")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleName": "   " }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("role.name_blank"));
    }

    // ---- restore ---------------------------------------------------------------------------------

    @Test
    void restoringARole_bringsBackItsPermissionsAndItsPeople() throws Exception {
        // Arrange
        String rootToken = rootToken();
        TestAgent holder = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "tenants:view");
        mockMvc.perform(delete("/api/roles/{uuid}", holder.roleId())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/tenants/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + holder.token()))
                .andExpect(status().isForbidden());

        // Act
        mockMvc.perform(post("/api/roles/{uuid}/restore", holder.roleId())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(holder.roleId().toString()));

        // Assert
        mockMvc.perform(get("/api/tenants/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + holder.token()))
                .andExpect(status().isNotFound());

        // a second restore answers the live role as it is
        mockMvc.perform(post("/api/roles/{uuid}/restore", holder.roleId())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk());
    }

    @Test
    void aDeletedRole_keepsItsPermissionsUntouched() throws Exception {
        // Arrange
        String rootToken = rootToken();
        UUID roleId = createRole(rootToken, "Deleted Role " + UUID.randomUUID().toString().substring(0, 8));
        mockMvc.perform(post("/api/role-permissions/append")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleId": "%s", "permissionName": "tenants:view" }
                                """.formatted(roleId)))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/api/roles/{uuid}", roleId)
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isNoContent());

        // Act + Assert
        mockMvc.perform(post("/api/role-permissions/revoke")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleId": "%s", "permissionName": "tenants:view" }
                                """.formatted(roleId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("role.unknown"));
        mockMvc.perform(get("/api/role-permissions/role/{roleId}", roleId)
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isNotFound());
    }

    // ---- what each change needs ------------------------------------------------------------------

    @Test
    void setPermissions_needsEditToGive_deleteToTake_andSaysWhatChanged() throws Exception {
        // Arrange
        String rootToken = rootToken();
        TestAgent giver = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "role_permissions:edit");
        UUID roleId = createRole(rootToken, "Set Role " + UUID.randomUUID().toString().substring(0, 8));

        // Act + Assert
        mockMvc.perform(post("/api/role-permissions/set")
                        .header("Authorization", "Bearer " + giver.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleId": "%s", "permissionNames": ["tenants:view", "tenants:edit"], "granted": true }
                                """.formatted(roleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.changed.length()").value(2));

        // already given: nothing changes, and nothing is said to have changed
        mockMvc.perform(post("/api/role-permissions/set")
                        .header("Authorization", "Bearer " + giver.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleId": "%s", "permissionNames": ["tenants:view"], "granted": true }
                                """.formatted(roleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed.length()").value(0));

        mockMvc.perform(post("/api/role-permissions/set")
                        .header("Authorization", "Bearer " + giver.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleId": "%s", "permissionNames": ["tenants:view"], "granted": false }
                                """.formatted(roleId)))
                .andExpect(status().isForbidden());

        // granted is required, never read as "take them away"
        mockMvc.perform(post("/api/role-permissions/set")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleId": "%s", "permissionNames": ["tenants:view"] }
                                """.formatted(roleId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void copyingARolesPermissions_needsRolePermissionsEdit() throws Exception {
        // Arrange
        String rootToken = rootToken();
        TestAgent maker = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "agent_roles:create");
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Act + Assert
        mockMvc.perform(post("/api/roles")
                        .header("Authorization", "Bearer " + maker.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleName": "Copy Of Admin %s", "copyFromRoleId": "%s" }
                                """.formatted(suffix, adminRoleId())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/roles")
                        .header("Authorization", "Bearer " + maker.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleName": "Plain Role %s" }
                                """.formatted(suffix)))
                .andExpect(status().isCreated());
    }

    @Test
    void restoring_needsAgentRolesEditToo() throws Exception {
        // Arrange
        String rootToken = rootToken();
        TestAgent deleter = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "agent_roles:delete");
        UUID roleId = createRole(rootToken, "Restore Role " + UUID.randomUUID().toString().substring(0, 8));
        mockMvc.perform(delete("/api/roles/{uuid}", roleId)
                        .header("Authorization", "Bearer " + deleter.token()))
                .andExpect(status().isNoContent());

        // Act + Assert
        mockMvc.perform(post("/api/roles/{uuid}/restore", roleId)
                        .header("Authorization", "Bearer " + deleter.token()))
                .andExpect(status().isForbidden());
    }

    // ---- the screens -----------------------------------------------------------------------------

    @Test
    void theRolePage_leavesOutPermissionsWithoutRolePermissionsView() throws Exception {
        // Arrange
        String rootToken = rootToken();
        TestAgent viewer = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "agent_roles:view", "audit:view");

        // Act + Assert
        mockMvc.perform(get("/api/screens/role/{roleId}", adminRoleId())
                        .header("Authorization", "Bearer " + viewer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role.builtIn").value(true))
                .andExpect(jsonPath("$.permissions").doesNotExist());

        mockMvc.perform(get("/api/auditlogs/record/agent_role/{id}", adminRoleId())
                        .param("field", "permissions.lots:edit")
                        .header("Authorization", "Bearer " + viewer.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/screens/role/{roleId}", adminRoleId())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").isArray());
    }

    @Test
    void aPersonsHistory_showsOnlyTheirRoles() throws Exception {
        // Arrange
        String rootToken = rootToken();
        TestAgent other = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "tenants:view");

        // Act + Assert
        mockMvc.perform(get("/api/auditlogs/record/agent/{id}", other.uuid())
                        .param("field", "workEmail")
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("audit.field_not_shown"));

        mockMvc.perform(get("/api/auditlogs/record/agent/{id}", other.uuid())
                        .param("field", "roles." + other.roleId())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1));
    }
}

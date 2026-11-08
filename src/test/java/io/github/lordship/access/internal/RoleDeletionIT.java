package io.github.lordship.access.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deleting a role has to take its permissions away from everyone holding it.
 *
 * <p>Before this was wired up, granted_role rows outlived the role they named and
 * findActivePermissionsForAgent never joined agent_role, so a deleted role kept
 * handing out its permissions indefinitely. Nothing in the suite noticed, because
 * no test deleted a role that anyone held.
 *
 * <p>/api/tenants is incidental here, as it is in AuthBoundaryIT -- a stable pair
 * of view/edit authorities and nothing more. 404 means the request got past the
 * gate and reached the handler; 403 means it did not.
 */
@Transactional
public class RoleDeletionIT extends IntegrationTest {

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

    /** An agent holding one freshly created role, and the id of that role. */
    private record Fixture(UUID agentId, UUID roleId, String token) {}

    private Fixture agentHoldingNewRoleWith(String rootToken, String permissionName) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String workEmail = "role-delete-" + suffix + "@example.test";
        String password = "integration-test-password";

        MvcResult registered = mockMvc.perform(post("/api/agents")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "nameFull": "Role Delete Agent %s",
                                  "workEmail": "%s",
                                  "password": "%s"
                                }
                                """.formatted(suffix, workEmail, password)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID agentId = UUID.fromString(objectMapper
                .readTree(registered.getResponse().getContentAsString())
                .get("id").asString());

        String roleName = "Role Delete Role " + suffix;

        MvcResult role = mockMvc.perform(post("/api/roles")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roleName": "%s",
                                  "roleDescription": "created by RoleDeletionIT"
                                }
                                """.formatted(roleName)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID roleId = UUID.fromString(objectMapper
                .readTree(role.getResponse().getContentAsString())
                .get("id").asString());

        mockMvc.perform(post("/api/role-permissions/append")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleId": "%s", "permissionName": "%s" }
                                """.formatted(roleId, permissionName)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/granted-roles/grant")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "agentId": "%s", "roleName": "%s" }
                                """.formatted(agentId, roleName)))
                .andExpect(status().isCreated());

        String token = TestAuthSupport.loginAsAgent(mockMvc, objectMapper, workEmail, password);
        return new Fixture(agentId, roleId, token);
    }

    @Test
    void deletingARole_takesItsPermissionsFromEveryHolder() throws Exception {
        // Arrange
        String rootToken = rootToken();
        Fixture fixture = agentHoldingNewRoleWith(rootToken, "tenants:view");

        mockMvc.perform(get("/api/tenants/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isNotFound());

        // Act
        mockMvc.perform(delete("/api/roles/{uuid}", fixture.roleId())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isNoContent());

        // Assert
        mockMvc.perform(get("/api/tenants/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + fixture.token()))
                .andExpect(status().isForbidden());
    }

    // The cascade, not just the query: the grant itself is gone, so the UI does not
    // go on listing a role the agent no longer effectively holds.
    @Test
    void deletingARole_revokesTheGrantsThatNamedIt() throws Exception {
        // Arrange
        String rootToken = rootToken();
        Fixture fixture = agentHoldingNewRoleWith(rootToken, "tenants:view");

        mockMvc.perform(get("/api/granted-roles/agent/{agentId}", fixture.agentId())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // Act
        mockMvc.perform(delete("/api/roles/{uuid}", fixture.roleId())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isNoContent());

        // Assert
        mockMvc.perform(get("/api/granted-roles/agent/{agentId}", fixture.agentId())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Admin is built in (RoleService.BUILT_IN), so it is never deleted: the server finds it by name,
     * ensureDefaultRoles would put an empty one back at startup, and root would be left holding nothing.
     * What it can do is still edited like any other role, and AccessKeys keeps the last keys.
     */
    @Test
    void deletingAdmin_isRefused_andRootKeepsItsPermissions() throws Exception {
        // Arrange
        String rootToken = rootToken();
        UUID adminRoleId = roleRepository.findByName("Admin")
                .orElseThrow(() -> new AssertionError("Admin role missing"))
                .uuid();

        // Act
        mockMvc.perform(delete("/api/roles/{uuid}", adminRoleId)
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("role.built_in"));

        // Assert
        mockMvc.perform(get("/api/tenants/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingARoleThatDoesNotExist_is404() throws Exception {
        mockMvc.perform(delete("/api/roles/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isNotFound());
    }
}
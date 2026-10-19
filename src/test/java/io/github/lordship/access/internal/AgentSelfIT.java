package io.github.lordship.access.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.TestAuthSupport.TestAgent;
import io.github.lordship.access.AgentLoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Sheet 1: the login response and GET /api/agents/self carry roles and permissions.
@Transactional
public class AgentSelfIT extends IntegrationTest {

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private String rootToken() throws Exception {
        return TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
    }

    @Test
    void login_includesRolesAndPermissions() throws Exception {
        AgentLoginRequest request = new AgentLoginRequest(rootEmail, rootPassword);

        mockMvc.perform(post("/api/agents/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("Admin")))
                .andExpect(jsonPath("$.permissions", hasItem("agents:view")))
                .andExpect(jsonPath("$.token").exists());
    }

    @Test
    void self_returnsSortedPermissions_andNoToken() throws Exception {
        TestAgent agent = TestAuthSupport.agentWithPermissions(
                mockMvc, objectMapper, rootToken(), "tenants:view", "agents:view");

        mockMvc.perform(get("/api/agents/self")
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(agent.uuid().toString()))
                .andExpect(jsonPath("$.workEmail").value(agent.workEmail()))
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.permissions", contains("agents:view", "tenants:view")))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void self_reflectsARevokedPermission_withTheOldToken() throws Exception {
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithPermissions(
                mockMvc, objectMapper, rootToken, "tenants:view", "agents:view");

        mockMvc.perform(post("/api/role-permissions/revoke")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleId": "%s", "permissionName": "tenants:view" }
                                """.formatted(agent.roleId())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/agents/self")
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", contains("agents:view")));
    }

    @Test
    void self_hasNoPermissions_forAnAgentWithOnlyTheUnassignedRole() throws Exception {
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);

        mockMvc.perform(post("/api/granted-roles/grant")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "agentId": "%s", "roleName": "Unassigned" }
                                """.formatted(agent.uuid())))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/agents/self")
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains("Unassigned")))
                .andExpect(jsonPath("$.permissions").isEmpty());
    }

    @Test
    void self_returns401_withoutAToken() throws Exception {
        mockMvc.perform(get("/api/agents/self"))
                .andExpect(status().isUnauthorized());
    }
}

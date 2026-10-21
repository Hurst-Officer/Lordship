package io.github.lordship;

import io.github.lordship.TestAuthSupport.TestAgent;
import io.github.lordship.shared.SystemPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Sheet 2: the Personnel read endpoints and DELETE /api/agents/{uuid}.
@Transactional
public class PersonnelIT extends IntegrationTest {

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

    private ResultActions getAs(String token, String path, Object... vars) throws Exception {
        return mockMvc.perform(get(path, vars).header("Authorization", "Bearer " + token));
    }

    // TestAuthSupport names every agent "IT Agent <suffix>" and mails it to it-agent-<suffix>@...
    private static String nameSuffix(TestAgent agent) {
        return agent.workEmail().substring("it-agent-".length(), agent.workEmail().indexOf('@'));
    }

    private void grantRole(String rootToken, UUID agentId, String roleName) throws Exception {
        mockMvc.perform(post("/api/granted-roles/grant")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "agentId": "%s", "roleName": "%s" }
                                """.formatted(agentId, roleName)))
                .andExpect(status().isCreated());
    }

    // ---- A. global view -------------------------------------------------------

    @Test
    void overview_listsRoleHoldersUnderTheirRole_andRoleLessAgentsUnderNoRole() throws Exception {
        String rootToken = rootToken();
        TestAgent viewer = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "agents:view");
        TestAgent loner = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);

        getAs(viewer.token(), "/api/personnel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[?(@.roleId == '%s')].agents[*].agentId".formatted(viewer.roleId()),
                        hasItem(viewer.uuid().toString())))
                .andExpect(jsonPath("$.noRole[*].agentId", hasItem(loner.uuid().toString())))
                .andExpect(jsonPath("$.noRole[*].agentId", not(hasItem(viewer.uuid().toString()))))
                .andExpect(jsonPath("$.noRole[*].agentId", not(hasItem(SystemPrincipal.AGENT_UUID.toString()))));
    }

    @Test
    void anAgentWithOnlyTheUnassignedRole_isRefusedEverywhere() throws Exception {
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);
        grantRole(rootToken, agent.uuid(), "Unassigned");

        getAs(agent.token(), "/api/personnel").andExpect(status().isForbidden());
        getAs(agent.token(), "/api/personnel/agents/{id}", agent.uuid()).andExpect(status().isForbidden());
        getAs(agent.token(), "/api/personnel/search?q=a").andExpect(status().isForbidden());
    }

    // ---- B. role view ---------------------------------------------------------

    @Test
    void role_marksEveryPermission_andSeesAnAppendRightAway() throws Exception {
        String rootToken = rootToken();
        TestAgent viewer = TestAuthSupport.agentWithPermissions(
                mockMvc, objectMapper, rootToken, "role_permissions:view", "permissions:view");

        getAs(viewer.token(), "/api/personnel/roles/{id}", viewer.roleId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents[*].agentId", contains(viewer.uuid().toString())))
                .andExpect(jsonPath("$.permissions[?(@.permissionName == 'permissions:view')].granted", contains(true)))
                .andExpect(jsonPath("$.permissions[?(@.permissionName == 'tenants:view')].granted", contains(false)));

        mockMvc.perform(post("/api/role-permissions/append")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleId": "%s", "permissionName": "tenants:view" }
                                """.formatted(viewer.roleId())))
                .andExpect(status().isCreated());

        getAs(viewer.token(), "/api/personnel/roles/{id}", viewer.roleId())
                .andExpect(jsonPath("$.permissions[?(@.permissionName == 'tenants:view')].granted", contains(true)));
    }

    @Test
    void role_needsRolePermissionsView() throws Exception {
        TestAgent viewer = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken(), "agents:view");

        getAs(viewer.token(), "/api/personnel/roles/{id}", viewer.roleId())
                .andExpect(status().isForbidden());
    }

    @Test
    void role_returns404_forAnUnknownRole() throws Exception {
        getAs(rootToken(), "/api/personnel/roles/{id}", UUID.randomUUID())
                .andExpect(status().isNotFound());
    }

    // ---- C. agent view --------------------------------------------------------

    @Test
    void agent_leavesOutEffectivePermissions_withoutRolePermissionsView() throws Exception {
        TestAgent viewer = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken(), "agents:view");

        getAs(viewer.token(), "/api/personnel/agents/{id}", viewer.uuid())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workEmail").value(viewer.workEmail()))
                .andExpect(jsonPath("$.roles[*].roleId", contains(viewer.roleId().toString())))
                .andExpect(jsonPath("$.effectivePermissions").doesNotExist())
                .andExpect(jsonPath("$.personalPhone").doesNotExist())
                .andExpect(jsonPath("$.personalEmail").doesNotExist());
    }

    @Test
    void agent_includesSortedEffectivePermissions_withRolePermissionsView() throws Exception {
        TestAgent viewer = TestAuthSupport.agentWithPermissions(
                mockMvc, objectMapper, rootToken(), "role_permissions:view", "agents:view");

        getAs(viewer.token(), "/api/personnel/agents/{id}", viewer.uuid())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectivePermissions", contains("agents:view", "role_permissions:view")));
    }

    @Test
    void agentAndPropertyViews_showAnAssignment() throws Exception {
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "agents:view");
        UUID propertyId = testData.insertProperty("Personnel Park", "1 Test Way", "PNL").uuid();

        mockMvc.perform(post("/api/property-assignments/assign")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "agentId": "%s", "propertyId": "%s" }
                                """.formatted(agent.uuid(), propertyId)))
                .andExpect(status().isCreated());

        getAs(agent.token(), "/api/personnel/agents/{id}", agent.uuid())
                .andExpect(jsonPath("$.properties[*].propertyId", contains(propertyId.toString())))
                .andExpect(jsonPath("$.properties[0].propertyCode").value("PNL"))
                .andExpect(jsonPath("$.properties[0].assignmentId").exists());

        getAs(agent.token(), "/api/personnel/property/{id}", propertyId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].agentId", contains(agent.uuid().toString())))
                .andExpect(jsonPath("$[0].roleNames", hasSize(1)));
    }

    @Test
    void propertyView_returns404_forAnUnknownProperty() throws Exception {
        getAs(rootToken(), "/api/personnel/property/{id}", UUID.randomUUID())
                .andExpect(status().isNotFound());
    }

    // ---- D. name search -------------------------------------------------------

    @Test
    void search_matchesPartOfTheName_ignoringCase() throws Exception {
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken(), "agents:view");

        getAs(agent.token(), "/api/personnel/search?q={q}", nameSuffix(agent).toUpperCase())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].agentId", contains(agent.uuid().toString())))
                .andExpect(jsonPath("$[0].roleNames", hasSize(1)));
    }

    @Test
    void search_returnsAtMostTen() throws Exception {
        String rootToken = rootToken();
        for (int i = 0; i < 11; i++) {
            TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);
        }

        getAs(rootToken, "/api/personnel/search?q={q}", "it agent")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(10)));
    }

    @Test
    void search_treatsPercentAsAPlainCharacter() throws Exception {
        String rootToken = rootToken();
        TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);

        getAs(rootToken, "/api/personnel/search?q={q}", "%")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---- remove an agent ------------------------------------------------------

    @Test
    void remove_hidesTheAgent_andTheirTokenStopsWorking() throws Exception {
        String rootToken = rootToken();
        TestAgent doomed = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "agents:view");

        mockMvc.perform(delete("/api/agents/{id}", doomed.uuid())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isNoContent());

        getAs(rootToken, "/api/personnel")
                .andExpect(jsonPath("$.roles[?(@.roleId == '%s')].agents[*]".formatted(doomed.roleId()), hasSize(0)));
        getAs(rootToken, "/api/personnel/agents/{id}", doomed.uuid())
                .andExpect(status().isNotFound());
        getAs(rootToken, "/api/personnel/search?q={q}", nameSuffix(doomed))
                .andExpect(jsonPath("$", hasSize(0)));
        getAs(doomed.token(), "/api/agents/self")
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/agents/{id}", doomed.uuid())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void remove_refusesToRemoveYourself() throws Exception {
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken(), "agents:delete");

        mockMvc.perform(delete("/api/agents/{id}", agent.uuid())
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("agent.cannot_remove_self"));
    }

    // root is the only Admin in the test database, so removing root is removing the last Admin
    @Test
    void remove_refusesToRemoveTheLastAdmin() throws Exception {
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken(), "agents:delete");
        UUID rootId = testData.findRootAgent().uuid();

        mockMvc.perform(delete("/api/agents/{id}", rootId)
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("agent.last_admin"));
    }

    @Test
    void remove_allowsRemovingAnAdmin_whenAnotherAdminRemains() throws Exception {
        String rootToken = rootToken();
        TestAgent secondAdmin = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);
        grantRole(rootToken, secondAdmin.uuid(), "Admin");

        mockMvc.perform(delete("/api/agents/{id}", secondAdmin.uuid())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void remove_returns404_forTheSystemAgent() throws Exception {
        mockMvc.perform(delete("/api/agents/{id}", SystemPrincipal.AGENT_UUID)
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isNotFound());
    }
}

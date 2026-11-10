package io.github.lordship.access.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.TestAuthSupport.TestAgent;
import io.github.lordship.access.internal.role.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An admin takes one permission away from one agent, even though a role of the agent's gives that
 * permission, and gives the permission back (/api/denied-permissions, DeniedPermissionService).
 *
 * <p>Every test rolls back, so every check after a change is made with the same token: a pass means the
 * agent's cached permissions were dropped, not that a TTL ran out.
 *
 * <p>/api/tenants is incidental, as in RoleEditingIT: 404 means the request got past the gate
 * (tenants:view), 403 that the request did not.
 */
@Transactional
public class DeniedPermissionIT extends IntegrationTest {

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

    @Autowired
    JdbcClient jdbc;

    private String rootToken() throws Exception {
        return TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
    }

    private ResultActions deny(String token, UUID agentId, String permissionName) throws Exception {
        return mockMvc.perform(post("/api/denied-permissions/deny")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "agentId": "%s", "permissionName": "%s" }
                        """.formatted(agentId, permissionName)));
    }

    private ResultActions restore(String token, UUID agentId, String permissionName) throws Exception {
        return mockMvc.perform(post("/api/denied-permissions/restore")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "agentId": "%s", "permissionName": "%s" }
                        """.formatted(agentId, permissionName)));
    }

    private ResultActions listDenials(String token, UUID agentId) throws Exception {
        return mockMvc.perform(get("/api/denied-permissions/agent/{agentId}", agentId)
                .header("Authorization", "Bearer " + token));
    }

    private void getTenant(String token, ResultMatcher expected) throws Exception {
        mockMvc.perform(get("/api/tenants/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(expected);
    }

    private ResultActions personScreen(String token, UUID agentId) throws Exception {
        return mockMvc.perform(get("/api/screens/person/{agentId}", agentId)
                .header("Authorization", "Bearer " + token));
    }

    // A new role holding one permission, given to the agent
    private UUID grantRoleWith(String rootToken, UUID agentId, String permissionName) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/roles")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleName": "Second Role %s" }
                                """.formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID roleId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asString());
        mockMvc.perform(post("/api/role-permissions/append")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleId": "%s", "permissionName": "%s" }
                                """.formatted(roleId, permissionName)))
                .andExpect(status().isCreated());
        grantRole(rootToken, agentId, roleId);
        return roleId;
    }

    private void grantRole(String rootToken, UUID agentId, UUID roleId) throws Exception {
        mockMvc.perform(post("/api/granted-roles/grant")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "agentId": "%s", "roleId": "%s" }
                                """.formatted(agentId, roleId)))
                .andExpect(status().isCreated());
    }

    private UUID adminRoleId() {
        return roleRepository.findByName("Admin").orElseThrow(() -> new AssertionError("Admin role missing")).uuid();
    }

    // ---- taking a permission away and giving the permission back --------------------------------

    @Test
    void deny_takesOnePermissionAway_andTheAgentKeepsTheRoleAndTheRest() throws Exception {
        // Arrange
        String rootToken = rootToken();
        UUID rootId = testData.findRootAgent().uuid();
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "tenants:view", "agents:view");
        getTenant(agent.token(), status().isNotFound());

        // Act
        deny(rootToken, agent.uuid(), "tenants:view")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.agentId").value(agent.uuid().toString()))
                .andExpect(jsonPath("$.permissionName").value("tenants:view"))
                .andExpect(jsonPath("$.deniedBy").value(rootId.toString()));

        // Assert: the very next request with the same token is refused
        getTenant(agent.token(), status().isForbidden());
        mockMvc.perform(get("/api/agents/self")
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.permissions", contains("agents:view")));
        listDenials(rootToken, agent.uuid())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].permissionName").value("tenants:view"));
    }

    @Test
    void restore_givesThePermissionBack_onTheNextRequest() throws Exception {
        // Arrange
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "tenants:view");
        deny(rootToken, agent.uuid(), "tenants:view").andExpect(status().isCreated());
        getTenant(agent.token(), status().isForbidden());

        // Act
        restore(rootToken, agent.uuid(), "tenants:view").andExpect(status().isNoContent());

        // Assert
        getTenant(agent.token(), status().isNotFound());
        listDenials(rootToken, agent.uuid())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // nothing left to give back
        restore(rootToken, agent.uuid(), "tenants:view").andExpect(status().isNotFound());
    }

    @Test
    void denyingTwice_answersTheSameDenial() throws Exception {
        // Arrange
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "tenants:view");
        MvcResult first = deny(rootToken, agent.uuid(), "tenants:view").andExpect(status().isCreated()).andReturn();
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asString();

        // Act + Assert: a double-click is not a 409 from the unique index
        deny(rootToken, agent.uuid(), "tenants:view")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(firstId));
        listDenials(rootToken, agent.uuid())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ---- what cannot be denied --------------------------------------------------------------------

    @Test
    void aPermissionNoRoleGives_cannotBeDenied() throws Exception {
        // Arrange
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "tenants:view");
        TestAgent noRole = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);

        // Act + Assert: a real permission the agent's role does not give
        deny(rootToken, agent.uuid(), "tenants:edit")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("permission.not_held"))
                .andExpect(jsonPath("$.field").value("permissionName"));

        // an agent with no role at all
        deny(rootToken, noRole.uuid(), "tenants:view")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("permission.not_held"));

        listDenials(rootToken, agent.uuid()).andExpect(jsonPath("$.length()").value(0));
        listDenials(rootToken, noRole.uuid()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unknownAgentsAndPermissions_areRefused() throws Exception {
        // Arrange
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "tenants:view");
        UUID nobody = UUID.randomUUID();

        // Act + Assert
        deny(rootToken, agent.uuid(), "tenants:nothing")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("permission.unknown"));
        restore(rootToken, agent.uuid(), "tenants:nothing")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("permission.unknown"));
        deny(rootToken, agent.uuid(), "  ")
                .andExpect(status().isBadRequest());

        deny(rootToken, nobody, "tenants:view").andExpect(status().isNotFound());
        restore(rootToken, nobody, "tenants:view").andExpect(status().isNotFound());
        listDenials(rootToken, nobody).andExpect(status().isNotFound());
    }

    // ---- a denial against the roles ---------------------------------------------------------------

    @Test
    void aDenial_holdsAgainstEveryRoleThatGivesThePermission() throws Exception {
        // Arrange: two roles of the agent's both give tenants:view
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "tenants:view");
        grantRoleWith(rootToken, agent.uuid(), "tenants:view");

        // Act
        deny(rootToken, agent.uuid(), "tenants:view").andExpect(status().isCreated());

        // Assert
        getTenant(agent.token(), status().isForbidden());
        personScreen(rootToken, agent.uuid())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[*].permissionName", not(hasItem("tenants:view"))))
                .andExpect(jsonPath("$.denied.length()").value(1))
                .andExpect(jsonPath("$.denied[0].permissionName").value("tenants:view"))
                .andExpect(jsonPath("$.denied[0].permissionOrder").isNumber())
                .andExpect(jsonPath("$.denied[0].through.length()").value(2));
    }

    @Test
    void aDenial_outlivesLosingAndRegainingTheRole() throws Exception {
        // Arrange
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "tenants:view");
        deny(rootToken, agent.uuid(), "tenants:view").andExpect(status().isCreated());
        mockMvc.perform(post("/api/granted-roles/revoke")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "agentId": "%s", "roleId": "%s" }
                                """.formatted(agent.uuid(), agent.roleId())))
                .andExpect(status().isNoContent());

        // while no role gives the permission, the person screen has nothing to list
        personScreen(rootToken, agent.uuid())
                .andExpect(jsonPath("$.denied.length()").value(0));
        listDenials(rootToken, agent.uuid())
                .andExpect(jsonPath("$.length()").value(1));

        // Act
        grantRole(rootToken, agent.uuid(), agent.roleId());

        // Assert
        getTenant(agent.token(), status().isForbidden());
        personScreen(rootToken, agent.uuid())
                .andExpect(jsonPath("$.denied[0].permissionName").value("tenants:view"));
    }

    // ---- the keys to the role editor --------------------------------------------------------------

    @Test
    void denyingTheLastKeys_isRefused_andLeavesNoDenialBehind() throws Exception {
        // Arrange: one agent holds the keys; Admin loses one key, so root no longer has them all
        String rootToken = rootToken();
        TestAgent keeper = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, KEYS);
        mockMvc.perform(post("/api/role-permissions/revoke")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "roleId": "%s", "permissionName": "agent_roles:edit" }
                                """.formatted(adminRoleId())))
                .andExpect(status().isNoContent());

        // Act + Assert
        deny(rootToken, keeper.uuid(), "role_permissions:edit")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("access.last_key"));

        // the refused denial was rolled back, and the keeper still manages roles
        listDenials(rootToken, keeper.uuid()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/roles/{uuid}", keeper.roleId())
                        .header("Authorization", "Bearer " + keeper.token()))
                .andExpect(status().isOk());
    }

    @Test
    void denyingKeysToOneOfTwoHolders_isAllowed() throws Exception {
        // Arrange: root (Admin) and a second agent both hold the keys
        String rootToken = rootToken();
        TestAgent keeper = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, KEYS);

        // Act + Assert
        deny(rootToken, keeper.uuid(), "role_permissions:edit").andExpect(status().isCreated());
    }

    // ---- what each change needs -------------------------------------------------------------------

    @Test
    void denyNeedsAgentRolesDelete_restoreNeedsAgentRolesEdit_listNeedsAgentRolesView() throws Exception {
        // Arrange
        String rootToken = rootToken();
        TestAgent target = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "tenants:view");
        TestAgent editor = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "agent_roles:edit");
        TestAgent deleter = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "agent_roles:delete");
        TestAgent viewer = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "agent_roles:view");

        // Act + Assert
        deny(editor.token(), target.uuid(), "tenants:view").andExpect(status().isForbidden());
        deny(deleter.token(), target.uuid(), "tenants:view").andExpect(status().isCreated());

        restore(deleter.token(), target.uuid(), "tenants:view").andExpect(status().isForbidden());

        listDenials(target.token(), target.uuid()).andExpect(status().isForbidden());
        listDenials(viewer.token(), target.uuid()).andExpect(status().isOk());

        restore(editor.token(), target.uuid(), "tenants:view").andExpect(status().isNoContent());
    }

    // ---- the person screen ------------------------------------------------------------------------

    @Test
    void thePersonScreen_leavesOutDenialsWithoutRolePermissionsView() throws Exception {
        // Arrange
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "tenants:view");
        TestAgent viewer = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "agent_roles:view");
        deny(rootToken, agent.uuid(), "tenants:view").andExpect(status().isCreated());

        // Act + Assert
        personScreen(viewer.token(), agent.uuid())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").doesNotExist())
                .andExpect(jsonPath("$.denied").doesNotExist());
        personScreen(rootToken, agent.uuid())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.denied.length()").value(1));
    }

    // ---- the record of who did what ---------------------------------------------------------------

    @Test
    void denyAndRestore_areOnThePersonsHistory_onlyForThoseWhoMaySeeWhatRolesDo() throws Exception {
        // Arrange: someone who may read history and see roles, but not what roles can do
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "tenants:view");
        TestAgent auditor = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "agent_roles:view", "audit:view");
        deny(rootToken, agent.uuid(), "tenants:view").andExpect(status().isCreated());

        // Act
        restore(rootToken, agent.uuid(), "tenants:view").andExpect(status().isNoContent());

        // Assert: the bar under the permission's switch on the person page reads like the switch (false: taken away)
        mockMvc.perform(get("/api/auditlogs/record/agent/{id}", agent.uuid())
                        .param("field", "permissions.tenants:view")
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[*].changes[0].after", containsInAnyOrder(false, true)));
        personScreen(rootToken, agent.uuid())
                .andExpect(jsonPath("$.history.records[0].fields['permissions.tenants:view']").exists());

        // without role_permissions:view, what was taken from a person stays hidden, history included
        mockMvc.perform(get("/api/auditlogs/record/agent/{id}", agent.uuid())
                        .param("field", "permissions.tenants:view")
                        .header("Authorization", "Bearer " + auditor.token()))
                .andExpect(status().isForbidden());
        personScreen(auditor.token(), agent.uuid())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.records[0].fields['permissions.tenants:view']").doesNotExist());
    }

    @Test
    void denyAndRestore_areAudited_andTheRowSaysWhoGaveThePermissionBack() throws Exception {
        // Arrange
        String rootToken = rootToken();
        UUID rootId = testData.findRootAgent().uuid();
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "tenants:view");
        MvcResult denied = deny(rootToken, agent.uuid(), "tenants:view").andExpect(status().isCreated()).andReturn();
        UUID denialId = UUID.fromString(objectMapper.readTree(denied.getResponse().getContentAsString()).get("id").asString());

        // Act
        restore(rootToken, agent.uuid(), "tenants:view").andExpect(status().isNoContent());

        // Assert: one INSERT and one DELETE in the audit log, both by root
        List<Map<String, Object>> audit = jdbc.sql("""
                        SELECT operation::text AS operation, user_id
                        FROM audit_log
                        WHERE table_name = 'denied_permission' AND record_id = :id
                        ORDER BY operation
                        """)
                .param("id", denialId.toString())
                .query()
                .listOfRows();
        assertEquals(2, audit.size(), "one audit row for the denial and one for giving the permission back");
        assertEquals("DELETE", audit.get(0).get("operation"));
        assertEquals("INSERT", audit.get(1).get("operation"));
        assertEquals(rootId, audit.get(0).get("user_id"));
        assertEquals(rootId, audit.get(1).get("user_id"));

        Map<String, Object> row = jdbc.sql("""
                        SELECT denied_by, denial_removed_by, deleted_at
                        FROM denied_permission
                        WHERE uuid = :id
                        """)
                .param("id", denialId)
                .query()
                .singleRow();
        assertEquals(rootId, row.get("denied_by"));
        assertEquals(rootId, row.get("denial_removed_by"));
        assertNotNull(row.get("deleted_at"), "giving the permission back is a soft delete");
    }
}

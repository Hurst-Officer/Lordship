package io.github.lordship;

import io.github.lordship.access.AgentLoginRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;



public final class TestAuthSupport {

    private TestAuthSupport() {}

    /**
     * An agent created for one test, with the token it logs in under.
     *
     * <p>roleId is the role the requested permissions were hung on, so a test can go
     * back and change it -- append a permission, revoke the grant, delete the role.
     * It is null when no permissions were asked for, since no role is created then.
     */
    public record TestAgent(UUID uuid, UUID roleId, String workEmail, String password, String token) {}

    // helper method
    public static String loginAsRoot(MockMvc mockMvc, ObjectMapper objectMapper,
                                     String rootEmail, String rootPassword) throws Exception {

        AgentLoginRequest agentLoginRequest = new AgentLoginRequest(rootEmail, rootPassword);

        MvcResult mvcResult = mockMvc.perform(post("/api/agents/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(agentLoginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workEmail").value(rootEmail))
                .andExpect(jsonPath("$.nameFull").value("Root Admin"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.token").exists())
                .andReturn();

        // --- Step 2: get token to use to authenticate registering another user
        String responseBody = mvcResult.getResponse().getContentAsString();
        return objectMapper.readTree(responseBody).get("token").asString();
    }

    public static String loginAsAgent(MockMvc mockMvc, ObjectMapper objectMapper, String email, String password) throws Exception {

        AgentLoginRequest agentLoginRequest = new AgentLoginRequest(email, password);

        MvcResult mvcResult = mockMvc.perform(post("/api/agents/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(agentLoginRequest)))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.token").exists())
                .andReturn();

        String responseBody = mvcResult.getResponse().getContentAsString();
        return objectMapper.readTree(responseBody).get("token").asString();
    }

    // This helper creates a fresh agent with only the permissions you name, so tests never leak roles into each other.
    // If you reference a permission or role that doesn’t exist, the test fails immediately instead of silently granting nothing.
    // Agents are registered over HTTP with the root token so audit logs record a real acting principal.
    public static TestAgent agentWithPermissions(MockMvc mockMvc,
                                                 ObjectMapper objectMapper,
                                                 String rootToken,
                                                 String... permissionNames) throws Exception {

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String workEmail = "it-agent-" + suffix + "@example.test";
        String password = "integration-test-password";

        MvcResult registered = mockMvc.perform(post("/api/agents")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "nameFull": "IT Agent %s",
                                  "workEmail": "%s",
                                  "password": "%s"
                                }
                                """.formatted(suffix, workEmail, password)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID agentId = UUID.fromString(objectMapper
                .readTree(registered.getResponse().getContentAsString())
                .get("id").asString());

        UUID roleId = null;

        if (permissionNames.length > 0) {
            String roleName = "IT Role " + suffix;

            MvcResult role = mockMvc.perform(post("/api/roles")
                            .header("Authorization", "Bearer " + rootToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "roleName": "%s",
                                      "roleDescription": "created by an integration test"
                                    }
                                    """.formatted(roleName)))
                    .andExpect(status().isCreated())
                    .andReturn();

            roleId = UUID.fromString(objectMapper
                    .readTree(role.getResponse().getContentAsString())
                    .get("id").asString());

            for (String permissionName : permissionNames) {
                mockMvc.perform(post("/api/role-permissions/append")
                                .header("Authorization", "Bearer " + rootToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "roleId": "%s", "permissionName": "%s" }
                                        """.formatted(roleId, permissionName)))
                        .andExpect(status().isCreated());
            }

            mockMvc.perform(post("/api/granted-roles/grant")
                            .header("Authorization", "Bearer " + rootToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "agentId": "%s", "roleName": "%s" }
                                    """.formatted(agentId, roleName)))
                    .andExpect(status().isCreated());
        }

        String token = loginAsAgent(mockMvc, objectMapper, workEmail, password);
        return new TestAgent(agentId, roleId, workEmail, password, token);
    }

    // create An agent with no role at all. registerAgent grants none, so this agent
    public static TestAgent agentWithNoPermissions(MockMvc mockMvc,
                                                   ObjectMapper objectMapper,
                                                   String rootToken) throws Exception {
        return agentWithPermissions(mockMvc, objectMapper, rootToken);
    }
}
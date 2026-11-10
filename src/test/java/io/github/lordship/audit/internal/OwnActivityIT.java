package io.github.lordship.audit.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.TestAuthSupport.TestAgent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A worker's own activity ("My activity" and the history on their own page in Glade) needs audit:view_own
 * ("See own activity log") alone. audit:view ("See activity logs", the history bars on every screen) does not
 * include it, so taking "See own activity log" away hides the worker's own activity even when a role of theirs
 * still lets them see activity logs.
 */
@Transactional
public class OwnActivityIT extends IntegrationTest {

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

    private void myActivity(TestAgent agent, int expectedStatus) throws Exception {
        mockMvc.perform(get("/api/auditlogs/mine")
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().is(expectedStatus));
    }

    private ResultActions selfScreen(TestAgent agent) throws Exception {
        return mockMvc.perform(get("/api/screens/self")
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isOk());
    }

    private void changePermission(String rootToken, TestAgent agent, String path, int expectedStatus) throws Exception {
        mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "agentId": "%s", "permissionName": "audit:view_own" }
                                """.formatted(agent.uuid())))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void ownActivity_needsSeeOwnActivityLog_notSeeActivityLogs() throws Exception {
        // Arrange
        String rootToken = rootToken();
        TestAgent seesLogs = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "audit:view");
        TestAgent seesOwn = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "audit:view_own");

        // Act + Assert: My activity
        myActivity(seesLogs, 403);
        myActivity(seesOwn, 200);

        // the history on their own page
        String withoutOwn = selfScreen(seesLogs)
                .andExpect(jsonPath("$.history").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        selfScreen(seesOwn).andExpect(jsonPath("$.history").exists());

        // the bars on their own page: an agent's own fields, and their own person record
        String personId = objectMapper.readTree(withoutOwn).get("personId").asString();
        mockMvc.perform(get("/api/auditlogs/record/agent/{id}", seesLogs.uuid())
                        .param("field", "workPhone")
                        .header("Authorization", "Bearer " + seesLogs.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("audit.field_not_shown"));
        mockMvc.perform(get("/api/auditlogs/record/person/{id}", personId)
                        .param("field", "nameFull")
                        .header("Authorization", "Bearer " + seesLogs.token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/auditlogs/record/agent/{id}", seesOwn.uuid())
                        .param("field", "workPhone")
                        .header("Authorization", "Bearer " + seesOwn.token()))
                .andExpect(status().isOk());
    }

    @Test
    void takingSeeOwnActivityLogAway_hidesMyActivity_evenWithSeeActivityLogs() throws Exception {
        // Arrange: a role gives both, like the seeded Property Manager
        String rootToken = rootToken();
        TestAgent manager = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken,
                "audit:view", "audit:view_own");
        myActivity(manager, 200);

        // Act: "See own activity log" taken from them alone
        changePermission(rootToken, manager, "/api/denied-permissions/deny", 201);

        // Assert: the very next request with the same token is refused, and their page has no history
        myActivity(manager, 403);
        selfScreen(manager).andExpect(jsonPath("$.history").doesNotExist());

        // given back, it opens again
        changePermission(rootToken, manager, "/api/denied-permissions/restore", 204);
        myActivity(manager, 200);
    }
}

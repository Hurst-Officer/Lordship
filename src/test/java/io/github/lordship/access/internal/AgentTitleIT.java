package io.github.lordship.access.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.TestAuthSupport.TestAgent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// An agent's job title: PUT /api/agents/{uuid}/title, shown in Personnel
@Transactional
public class AgentTitleIT extends IntegrationTest {

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbc;

    private String rootToken() throws Exception {
        return TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
    }

    private ResultActions setTitle(String token, UUID agentId, String title) throws Exception {
        return mockMvc.perform(put("/api/agents/{uuid}/title", agentId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("title", title))));
    }

    private int titleAuditRows(UUID agentId) {
        return jdbc.sql("""
                SELECT count(*) FROM audit_log
                WHERE table_name = 'agent' AND record_id = :id AND operation = 'UPDATE'
                AND value_after LIKE '%title%'
                """)
                .param("id", agentId.toString())
                .query(Integer.class)
                .single();
    }

    @Test
    void aNewAgent_isTitledAgent() throws Exception {
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);

        mockMvc.perform(get("/api/personnel/agents/{id}", agent.uuid())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Agent"));
    }

    @Test
    void setTitle_changesIt_andDropsOuterSpaces() throws Exception {
        String rootToken = rootToken();
        TestAgent editor = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "agents:edit");
        TestAgent target = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);

        setTitle(editor.token(), target.uuid(), "  Property Manager  ")
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/personnel/agents/{id}", target.uuid())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(jsonPath("$.title").value("Property Manager"));
    }

    @Test
    void setTitle_isAudited_onlyWhenItChanges() throws Exception {
        String rootToken = rootToken();
        TestAgent target = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);

        setTitle(rootToken, target.uuid(), "Groundskeeper").andExpect(status().isNoContent());
        setTitle(rootToken, target.uuid(), "Groundskeeper").andExpect(status().isNoContent());

        assertEquals(1, titleAuditRows(target.uuid()));
    }

    @Test
    void setTitle_refusesABlankTitle() throws Exception {
        String rootToken = rootToken();
        TestAgent target = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);

        setTitle(rootToken, target.uuid(), "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("agent.title_blank"))
                .andExpect(jsonPath("$.field").value("title"));
    }

    @Test
    void setTitle_needsAgentsEdit() throws Exception {
        String rootToken = rootToken();
        TestAgent viewer = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "agents:view");
        TestAgent target = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);

        setTitle(viewer.token(), target.uuid(), "Manager").andExpect(status().isForbidden());
    }

    @Test
    void setTitle_returns404_forAnUnknownAgent() throws Exception {
        setTitle(rootToken(), UUID.randomUUID(), "Manager").andExpect(status().isNotFound());
    }
}

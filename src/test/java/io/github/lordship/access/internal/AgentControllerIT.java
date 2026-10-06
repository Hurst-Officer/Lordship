package io.github.lordship.access.internal;


import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.access.Agent;
import io.github.lordship.access.AgentLoginRequest;
import io.github.lordship.access.AgentService;
import io.github.lordship.access.internal.agents.AgentRegistrationRequest;
import io.github.lordship.access.internal.role.RoleController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Transactional
public class AgentControllerIT extends IntegrationTest {

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AgentService agentService;

    @Test
    void login_createsNewLoginEvent_whenUserLogsIn() throws Exception {
        // Arrange
        Optional<Agent> agent = agentService.findByWorkEmail(rootEmail);
        assertTrue(agent.isPresent());
        Agent rootAgent = agent.get();
        int logCount = agentService.getLoginEventsByAgentId(rootAgent.uuid()).size();

        // Act
        TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        List<LoginEventRow> loginEvents = agentService.getLoginEventsByAgentId(rootAgent.uuid());
        int logCountPostLogin = loginEvents.size();

        LoginEventRow topLog = loginEvents.getFirst();

        // Assert
        assertFalse(topLog.browserClient().isEmpty());
        assertFalse(topLog.agentId().toString().isEmpty());
        assertFalse(topLog.ipAddress().isEmpty());
        assertTrue(logCountPostLogin > logCount);
    }

    @Test
    void register_allowedForRootAgent_afterLogin() throws Exception {
        // Arrange
        String testAgentName = "Bilbo Baggins";
        String testAgentEmail = "baggins@lordship.com";
        String testAgentPass = "pass123456789";

        // steps 1 & 2: login and get token
        String rootUserToken = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        AgentRegistrationRequest agentRegistrationRequest = new AgentRegistrationRequest(testAgentName, null,
                testAgentEmail, null, testAgentPass);

        mockMvc.perform(post("/api/agents")
                     .header("Authorization", "Bearer " + rootUserToken) // don't forget your token!
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(agentRegistrationRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workEmail").value(testAgentEmail))
                .andExpect(jsonPath("$.nameFull").value(testAgentName))
                .andExpect(jsonPath("$.id").exists());

        // --- Step 4: ensure the new user can log in
        AgentLoginRequest newAgentLoginRequest = new AgentLoginRequest(testAgentEmail, testAgentPass);

        // Act
        MvcResult testUserLoginResult = mockMvc.perform(post("/api/agents/auth")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(newAgentLoginRequest)))
        // Assert
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workEmail").value(testAgentEmail))
                .andExpect(jsonPath("$.nameFull").value(testAgentName))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.token").exists())
                .andReturn();

        String testUserLoginResponseBody = testUserLoginResult.getResponse().getContentAsString();
        String userLoginToken = objectMapper.readTree(testUserLoginResponseBody).get("token").asString();

        assertFalse(userLoginToken.isEmpty());
    }

    @Test
    void register_shouldReturn401_whenAgentIsUnauthorized() throws Exception {
        // Arrange
        String testAgentName = "Bilbo Baggins";
        String testAgentNameEmail = "BigBaggins@gmail.com";
        String testAgentPassword = "pass123456789";

        AgentRegistrationRequest agentRegistrationRequest = new AgentRegistrationRequest(
                testAgentName,
                null, testAgentNameEmail,
                null, testAgentPassword);

        // Act
        mockMvc.perform(post("/api/agents")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(agentRegistrationRequest)))
        // Assert
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_shouldReturn401_whenPasswordIsIncorrect() throws Exception {
        // Arrange
        String wrongPassword = rootPassword + "123";
        AgentLoginRequest agentLoginRequest = new AgentLoginRequest(rootEmail, wrongPassword);

        // Act
        mockMvc.perform(post("/api/agents/auth")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(agentLoginRequest)))
        // Assert
                .andExpect(status().isUnauthorized())
                .andReturn();
    }

    @Test
    void shouldAllowRootUserToCreateRole_whenAuthorized() throws Exception {
        String testRoleName = "Super Loser";
        String testRoleDesc = "A role to know you're a loser";
        String rootUserToken = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        RoleController.RoleCreationRequest rcr = new RoleController.RoleCreationRequest(testRoleName, testRoleDesc);

        // first make sure the role can't be created if we're not logged in
        mockMvc.perform(post("/api/roles")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(rcr)))
                .andExpect(status().isUnauthorized());

        // second make sure the role can be created by the authorized user
        mockMvc.perform(post("/api/roles")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(rcr))
                    .header("Authorization", "Bearer " + rootUserToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roleName").value(testRoleName))
                .andExpect(jsonPath("$.id").exists());
    }
}
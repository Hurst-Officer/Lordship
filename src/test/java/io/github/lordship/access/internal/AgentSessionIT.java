package io.github.lordship.access.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.TestAuthSupport.TestAgent;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Signed-in computers: POST /api/agents/session/refresh and /api/agents/session/logout
@Transactional
public class AgentSessionIT extends IntegrationTest {

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private String rootToken() throws Exception {
        return TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
    }

    // Signs the agent in and returns the whole response body.
    private JsonNode signIn(TestAgent agent) throws Exception {
        String body = mockMvc.perform(post("/api/agents/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "workEmail": "%s", "password": "%s" }
                                """.formatted(agent.workEmail(), agent.password())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/agents/session/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "refreshToken": "%s" }
                        """.formatted(refreshToken)));
    }

    // A correctly signed access token that ran out an hour ago,
    // like the one Glade holds after a worker comes back from lunch.
    private String expiredAccessToken(TestAgent agent) {
        long anHourAgo = System.currentTimeMillis() - 3_600_000;
        return Jwts.builder()
                .subject(agent.uuid().toString())
                .claim("user_type", "AGENT")
                .issuedAt(new Date(anHourAgo - 900_000))
                .expiration(new Date(anHourAgo))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    // Asks the server which signed-in computer this access token belongs to.
    private String thisComputerId(String accessToken, TestAgent agent) throws Exception {
        String body = mockMvc.perform(get("/api/agents/{uuid}/sessions", agent.uuid())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode session : objectMapper.readTree(body)) {
            if (session.get("thisComputer").asBoolean()) {
                return session.get("id").asString();
            }
        }
        throw new AssertionError("No session was marked thisComputer");
    }

    private String refreshTokenFrom(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("refreshToken").asString();
    }

    @Test
    void signIn_returnsARefreshToken() throws Exception {
        TestAgent agent = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken());

        JsonNode body = signIn(agent);

        assertNotNull(body.get("refreshToken"));
    }

    @Test
    void refresh_returnsANewAccessTokenAndANewRefreshToken() throws Exception {
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken(), "agents:view");
        String first = signIn(agent).get("refreshToken").asString();

        ResultActions result = refresh(first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(agent.uuid().toString()))
                .andExpect(jsonPath("$.permissions[0]").value("agents:view"))
                .andExpect(jsonPath("$.token").exists());

        assertNotEquals(first, refreshTokenFrom(result));
    }

    @Test
    void refresh_refusesATokenThatWasAlreadyUsed_andEndsTheSession() throws Exception {
        TestAgent agent = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken());
        String first = signIn(agent).get("refreshToken").asString();
        String second = refreshTokenFrom(refresh(first).andExpect(status().isOk()));

        refresh(first)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.session_expired"));

        // the old token coming back ends the session, so the newest token stops too
        refresh(second).andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_catchesACopyFromSeveralSwapsAgo() throws Exception {
        TestAgent agent = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken());
        String stolen = signIn(agent).get("refreshToken").asString();
        String second = refreshTokenFrom(refresh(stolen).andExpect(status().isOk()));
        String third = refreshTokenFrom(refresh(second).andExpect(status().isOk()));

        // the thief tries the copy long after Glade moved on
        refresh(stolen).andExpect(status().isUnauthorized());

        // the session is over for everyone, so Glade signs in again too
        refresh(third).andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_refusesAnUnknownToken() throws Exception {
        refresh("not-a-real-token")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.session_expired"));
    }

    @Test
    void refresh_ignoresAnExpiredAccessTokenSentAlong() throws Exception {
        TestAgent agent = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken());
        String refreshToken = signIn(agent).get("refreshToken").asString();
        String expired = expiredAccessToken(agent);

        // anywhere else, this token is turned away
        mockMvc.perform(get("/api/agents/self")
                        .header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());

        // the refresh call does not look at it
        mockMvc.perform(post("/api/agents/session/refresh")
                        .header("Authorization", "Bearer " + expired)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "refreshToken": "%s" }
                                """.formatted(refreshToken)))
                .andExpect(status().isOk());
    }

    @Test
    void logout_endsOnlyThatSession() throws Exception {
        TestAgent agent = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken());
        String office = signIn(agent).get("refreshToken").asString();
        String laptop = signIn(agent).get("refreshToken").asString();

        mockMvc.perform(post("/api/agents/session/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "refreshToken": "%s" }
                                """.formatted(office)))
                .andExpect(status().isNoContent());

        refresh(office).andExpect(status().isUnauthorized());
        refresh(laptop).andExpect(status().isOk());
    }

    @Test
    void logout_isQuietForAnUnknownToken() throws Exception {
        mockMvc.perform(post("/api/agents/session/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "refreshToken": "not-a-real-token" }
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    void passwordReset_endsEverySession() throws Exception {
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);
        String refreshToken = signIn(agent).get("refreshToken").asString();

        mockMvc.perform(put("/api/agents/{uuid}/password", agent.uuid())
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "newPassword": "a-brand-new-password" }
                                """))
                .andExpect(status().isNoContent());

        refresh(refreshToken).andExpect(status().isUnauthorized());
    }

    @Test
    void removal_endsEverySession() throws Exception {
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);
        String refreshToken = signIn(agent).get("refreshToken").asString();

        mockMvc.perform(delete("/api/agents/{uuid}", agent.uuid())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isNoContent());

        refresh(refreshToken).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshedAccessToken_works() throws Exception {
        TestAgent agent = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken());
        String refreshToken = signIn(agent).get("refreshToken").asString();

        String body = refresh(refreshToken).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(body).get("token").asString();

        mockMvc.perform(get("/api/agents/self")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(agent.uuid().toString()));
    }

    // ---- signed-in computers (admin) ----

    @Test
    void listSessions_showsEachSignedInComputer() throws Exception {
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);
        signIn(agent);
        signIn(agent);

        // one sign-in from the test helper, plus the two above
        mockMvc.perform(get("/api/agents/{uuid}/sessions", agent.uuid())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].tokenHash").doesNotExist());
    }

    @Test
    void listSessions_marksTheCallersOwnComputer() throws Exception {
        TestAgent agent = TestAuthSupport.agentWithPermissions(
                mockMvc, objectMapper, rootToken(), "agents:end_sessions");
        JsonNode office = signIn(agent);

        String id = thisComputerId(office.get("token").asString(), agent);

        assertNotNull(id);
    }

    @Test
    void signOutComputer_endsOnlyThatComputer() throws Exception {
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithPermissions(
                mockMvc, objectMapper, rootToken, "agents:end_sessions");
        JsonNode office = signIn(agent);
        JsonNode laptop = signIn(agent);
        String officeId = thisComputerId(office.get("token").asString(), agent);

        mockMvc.perform(delete("/api/agents/{uuid}/sessions/{sessionId}", agent.uuid(), officeId)
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isNoContent());

        refresh(office.get("refreshToken").asString()).andExpect(status().isUnauthorized());
        refresh(laptop.get("refreshToken").asString()).andExpect(status().isOk());
    }

    @Test
    void signOutComputer_is404_forAnotherAgentsSession() throws Exception {
        String rootToken = rootToken();
        TestAgent owner = TestAuthSupport.agentWithPermissions(
                mockMvc, objectMapper, rootToken, "agents:end_sessions");
        TestAgent other = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);
        String ownersId = thisComputerId(signIn(owner).get("token").asString(), owner);

        mockMvc.perform(delete("/api/agents/{uuid}/sessions/{sessionId}", other.uuid(), ownersId)
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void signOutEverywhere_endsEveryComputer() throws Exception {
        String rootToken = rootToken();
        TestAgent agent = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);
        String office = signIn(agent).get("refreshToken").asString();
        String laptop = signIn(agent).get("refreshToken").asString();

        mockMvc.perform(delete("/api/agents/{uuid}/sessions", agent.uuid())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isNoContent());

        refresh(office).andExpect(status().isUnauthorized());
        refresh(laptop).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/agents/{uuid}/sessions", agent.uuid())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void sessions_need_agentsEndSessions() throws Exception {
        String rootToken = rootToken();
        TestAgent viewer = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken, "agents:view");
        TestAgent target = TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);

        mockMvc.perform(get("/api/agents/{uuid}/sessions", target.uuid())
                        .header("Authorization", "Bearer " + viewer.token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/agents/{uuid}/sessions", target.uuid())
                        .header("Authorization", "Bearer " + viewer.token()))
                .andExpect(status().isForbidden());
    }
}

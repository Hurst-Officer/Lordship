package io.github.lordship.homes.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
public class SecuredPartyControllerIT extends IntegrationTest {

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

    private UUID lotOn(String propertyCode, String lotNumber) {
        UUID propertyId = testData.insertProperty(propertyCode).uuid();
        return testData.insertLot(propertyId, lotNumber).uuid();
    }

    private UUID createHome(String token, UUID lotId) throws Exception {
        String body = mockMvc.perform(post("/api/homes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(lotId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("uuid").asString());
    }

    private UUID person(String name) {
        return testData.insertPerson(name).uuid();
    }

    private String createBody(UUID mobileHomeId, UUID personId, String startDate) {
        return startDate == null
                ? """
                  { "mobileHomeId": "%s", "personId": "%s" }
                  """.formatted(mobileHomeId, personId)
                : """
                  { "mobileHomeId": "%s", "personId": "%s", "startDate": "%s" }
                  """.formatted(mobileHomeId, personId, startDate);
    }

    private UUID createSecuredParty(String token, UUID mobileHomeId, UUID personId, String startDate) throws Exception {
        String body = mockMvc.perform(post("/api/secured-parties")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(mobileHomeId, personId, startDate)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("uuid").asString());
    }

    // ── the auth boundary ────────────────────────────────────────────────────────

    @Test
    void createSecuredParty_shouldReturn401_whenNoToken() throws Exception {
        mockMvc.perform(post("/api/secured-parties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID(), UUID.randomUUID(), null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createSecuredParty_shouldReturn403_whenAgentHoldsOnlyHomesView() throws Exception {
        // Arrange: viewing is not creating
        String rootToken = rootToken();
        UUID mobileHomeId = createHome(rootToken, lotOn("SC01", "1"));
        TestAuthSupport.TestAgent agent = TestAuthSupport.agentWithPermissions(
                mockMvc, objectMapper, rootToken, "homes:view");

        // Act & Assert
        mockMvc.perform(post("/api/secured-parties")
                        .header("Authorization", "Bearer " + agent.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(mobileHomeId, person("First National Bank"), null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getByHome_shouldReturn200_whenAgentHoldsOnlyHomesView() throws Exception {
        String rootToken = rootToken();
        UUID mobileHomeId = createHome(rootToken, lotOn("SC02", "1"));
        createSecuredParty(rootToken, mobileHomeId, person("First National Bank"), null);

        TestAuthSupport.TestAgent agent = TestAuthSupport.agentWithPermissions(
                mockMvc, objectMapper, rootToken, "homes:view");

        mockMvc.perform(get("/api/secured-parties/home/{mobileHomeId}", mobileHomeId)
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── create ───────────────────────────────────────────────────────────────────

    @Test
    void createSecuredParty_shouldReturn201_andUseTheSuppliedStartDate() throws Exception {
        String token = rootToken();
        UUID mobileHomeId = createHome(token, lotOn("SC03", "1"));

        mockMvc.perform(post("/api/secured-parties")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(mobileHomeId, person("First National Bank"), "2026-10-01")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uuid").exists())
                .andExpect(jsonPath("$.mobileHomeId").value(mobileHomeId.toString()))
                .andExpect(jsonPath("$.startDate").value("2026-10-01"))
                .andExpect(jsonPath("$.endDate").doesNotExist())
                .andExpect(jsonPath("$.acceptPayments").value(false));
    }

    @Test
    void createSecuredParty_shouldFillInAStartDate_whenTheCallerOmitsIt() throws Exception {
        String token = rootToken();
        UUID mobileHomeId = createHome(token, lotOn("SC04", "1"));

        mockMvc.perform(post("/api/secured-parties")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(mobileHomeId, person("First National Bank"), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startDate").exists());
    }

    @Test
    void createSecuredParty_shouldReturn400_whenTheHomeDoesNotExist() throws Exception {
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(post("/api/secured-parties")
                        .header("Authorization", "Bearer " + rootToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(unknown, person("First National Bank"), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No mobile home " + unknown));
    }

    @Test
    void createSecuredParty_shouldReturn400_whenTheBodyIsIncomplete() throws Exception {
        mockMvc.perform(post("/api/secured-parties")
                        .header("Authorization", "Bearer " + rootToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "mobileHomeId": null, "personId": null }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createSecuredParty_shouldReturn409_whenThatPersonIsAlreadyActiveOnTheHome() throws Exception {
        String token = rootToken();
        UUID mobileHomeId = createHome(token, lotOn("SC05", "1"));
        UUID personId = person("First National Bank");

        createSecuredParty(token, mobileHomeId, personId, "2026-01-01");

        mockMvc.perform(post("/api/secured-parties")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(mobileHomeId, personId, "2026-03-01")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void createSecuredParty_shouldLeaveExistingSecuredPartiesActive() throws Exception {
        String token = rootToken();
        UUID mobileHomeId = createHome(token, lotOn("SC06", "1"));

        createSecuredParty(token, mobileHomeId, person("First National Bank"), "2026-01-01");
        createSecuredParty(token, mobileHomeId, person("Credit Union of Shelbyville"), "2026-03-01");

        mockMvc.perform(get("/api/secured-parties/home/{mobileHomeId}", mobileHomeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].endDate").doesNotExist())
                .andExpect(jsonPath("$[1].endDate").doesNotExist());
    }

    // ── reads ────────────────────────────────────────────────────────────────────

    @Test
    void getSecuredParty_shouldReturn404_whenItDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/secured-parties/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByHome_shouldReturnConcludedClaims_whenActiveOnlyIsFalse() throws Exception {
        String token = rootToken();
        UUID mobileHomeId = createHome(token, lotOn("SC07", "1"));
        UUID securedPartyId = createSecuredParty(token, mobileHomeId, person("First National Bank"), "2024-01-01");

        mockMvc.perform(patch("/api/secured-parties/{uuid}", securedPartyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "endDate": "2025-06-30" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endDate").value("2025-06-30"));

        mockMvc.perform(get("/api/secured-parties/home/{mobileHomeId}", mobileHomeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/secured-parties/home/{mobileHomeId}", mobileHomeId)
                        .param("activeOnly", "false")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void getByPerson_shouldReturnTheirSecuredPartyRecords() throws Exception {
        String token = rootToken();
        UUID personId = person("First National Bank");
        UUID mobileHomeId = createHome(token, lotOn("SC08", "1"));
        createSecuredParty(token, mobileHomeId, personId, "2026-01-01");

        mockMvc.perform(get("/api/secured-parties/person/{personId}", personId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].personId").value(personId.toString()));
    }

    // ── patch ────────────────────────────────────────────────────────────────────

    @Test
    void patchSecuredParty_shouldReturn400_whenEndDateIsBeforeStartDate() throws Exception {
        String token = rootToken();
        UUID mobileHomeId = createHome(token, lotOn("SC09", "1"));
        UUID securedPartyId = createSecuredParty(token, mobileHomeId, person("First National Bank"), "2026-06-01");

        mockMvc.perform(patch("/api/secured-parties/{uuid}", securedPartyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "endDate": "2026-01-01" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void patchSecuredParty_shouldReturn404_whenItDoesNotExist() throws Exception {
        mockMvc.perform(patch("/api/secured-parties/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + rootToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "endDate": "2026-01-01" }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchSecuredParty_shouldUpdateAcceptPayments() throws Exception {
        String token = rootToken();
        UUID mobileHomeId = createHome(token, lotOn("SC10", "1"));
        UUID securedPartyId = createSecuredParty(token, mobileHomeId, person("First National Bank"), "2026-01-01");

        mockMvc.perform(patch("/api/secured-parties/{uuid}", securedPartyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "acceptPayments": true }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptPayments").value(true));
    }

    @Test
    void patchSecuredParty_shouldIgnoreFieldsItDoesNotKnow() throws Exception {
        // the controller whitelists, so an unknown/non-mapped key is dropped rather
        // than refused -- and rather than silently applied, like personId would be
        String token = rootToken();
        UUID mobileHomeId = createHome(token, lotOn("SC11", "1"));
        UUID personId = person("First National Bank");
        UUID securedPartyId = createSecuredParty(token, mobileHomeId, personId, "2026-01-01");

        mockMvc.perform(patch("/api/secured-parties/{uuid}", securedPartyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "personId": "%s", "bogusColumn": 12 }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personId").value(personId.toString()));
    }

    @Test
    void patchSecuredParty_shouldReturn409_whenClearingTheEndDateWouldCollide() throws Exception {
        String token = rootToken();
        UUID mobileHomeId = createHome(token, lotOn("SC12", "1"));
        UUID personId = person("First National Bank");

        UUID first = createSecuredParty(token, mobileHomeId, personId, "2024-01-01");
        mockMvc.perform(patch("/api/secured-parties/{uuid}", first)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "endDate": "2025-06-30" }
                                """))
                .andExpect(status().isOk());

        // a second, currently-active claim for the same person on the same home
        createSecuredParty(token, mobileHomeId, personId, "2026-01-01");

        mockMvc.perform(patch("/api/secured-parties/{uuid}", first)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "endDate": null }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }

    // ── delete ───────────────────────────────────────────────────────────────────

    @Test
    void deleteSecuredParty_shouldReturn204_thenNotFound() throws Exception {
        String token = rootToken();
        UUID mobileHomeId = createHome(token, lotOn("SC13", "1"));
        UUID securedPartyId = createSecuredParty(token, mobileHomeId, person("First National Bank"), "2026-01-01");

        mockMvc.perform(delete("/api/secured-parties/{uuid}", securedPartyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/secured-parties/{uuid}", securedPartyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}

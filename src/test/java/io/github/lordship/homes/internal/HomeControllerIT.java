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
public class HomeControllerIT extends IntegrationTest {

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
        return UUID.fromString(objectMapper.readTree(body).get("id").asString());
    }

    // ── the auth boundary ────────────────────────────────────────────────────────

    @Test
    void createHome_shouldReturn401_whenNoToken() throws Exception {
        mockMvc.perform(post("/api/homes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listHomes_shouldReturn403_whenAgentLacksTheAuthority() throws Exception {
        // Arrange: authenticated, but granted nothing
        String rootToken = rootToken();
        TestAuthSupport.TestAgent agent =
                TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);
        UUID lotId = lotOn("HC01", "1");

        // Act & Assert: 403, not 401 -- the token is fine, the authority is missing.
        // This is the half of the rule an unauthenticated call can never exercise.
        mockMvc.perform(get("/api/homes")
                        .param("lotId", lotId.toString())
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void listHomes_shouldReturn200_whenAgentHoldsOnlyHomesView() throws Exception {
        // Arrange
        String rootToken = rootToken();
        UUID lotId = lotOn("HC02", "1");
        createHome(rootToken, lotId);

        TestAuthSupport.TestAgent agent = TestAuthSupport.agentWithPermissions(
                mockMvc, objectMapper, rootToken, "homes:view");

        // Act & Assert
        mockMvc.perform(get("/api/homes")
                        .param("lotId", lotId.toString())
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void createHome_shouldReturn403_whenAgentHoldsOnlyHomesView() throws Exception {
        // Arrange: viewing is not creating
        String rootToken = rootToken();
        UUID lotId = lotOn("HC03", "1");
        TestAuthSupport.TestAgent agent = TestAuthSupport.agentWithPermissions(
                mockMvc, objectMapper, rootToken, "homes:view");

        // Act & Assert
        mockMvc.perform(post("/api/homes")
                        .header("Authorization", "Bearer " + agent.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(lotId)))
                .andExpect(status().isForbidden());
    }

    // ── create ───────────────────────────────────────────────────────────────────

    @Test
    void createHome_shouldReturn201_withGeneratedNameAndDefaults() throws Exception {
        // Arrange
        String token = rootToken();
        UUID lotId = lotOn("HC04", "4B");

        // Act
        mockMvc.perform(post("/api/homes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(lotId)))
                // Assert: DB defaults plus the generated label, and nothing else --
                // the rest waits for a PATCH.
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.lotId").value(lotId.toString()))
                .andExpect(jsonPath("$.name").value("Mobile home on lot 4B"))
                .andExpect(jsonPath("$.dimensionsUnits").value("FT"))
                .andExpect(jsonPath("$.parkOwned").value(false))
                .andExpect(jsonPath("$.createdBy").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.sections").doesNotExist())
                .andExpect(jsonPath("$.condition").doesNotExist())
                .andExpect(jsonPath("$.vin").doesNotExist());
    }

    @Test
    void createHome_shouldReturn400_whenLotIdMissing() throws Exception {
        // Guards the DispatcherType.ERROR fix: bean validation failing used to forward
        // to /error and come back 401, which read as an auth problem.
        mockMvc.perform(post("/api/homes")
                        .header("Authorization", "Bearer " + rootToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createHome_shouldReturn400_whenLotDoesNotExist() throws Exception {
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(post("/api/homes")
                        .header("Authorization", "Bearer " + rootToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(unknown)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No lot " + unknown));
    }

    // ── the generated name ───────────────────────────────────────────────────────

    @Test
    void patchHome_shouldUpgradeTheGeneratedName_whenSectionsArrives() throws Exception {
        String token = rootToken();
        UUID homeId = createHome(token, lotOn("HC05", "4B"));

        mockMvc.perform(patch("/api/homes/" + homeId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "sections": 2 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Double wide on lot 4B"))
                .andExpect(jsonPath("$.sections").value(2));
    }

    @Test
    void patchHome_shouldLeaveAHumanNamedHomeAlone() throws Exception {
        String token = rootToken();
        UUID homeId = createHome(token, lotOn("HC06", "4B"));

        mockMvc.perform(patch("/api/homes/" + homeId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Blue one by the office" }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/homes/" + homeId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "sections": 3 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Blue one by the office"))
                .andExpect(jsonPath("$.sections").value(3));
    }

    @Test
    void patchHome_shouldFollowTheLot_whenAHomeIsMoved() throws Exception {
        String token = rootToken();
        UUID propertyId = testData.insertProperty("HC07").uuid();
        UUID from = testData.insertLot(propertyId, "4B").uuid();
        UUID to = testData.insertLot(propertyId, "9C").uuid();
        UUID homeId = createHome(token, from);

        mockMvc.perform(patch("/api/homes/" + homeId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(to)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mobile home on lot 9C"))
                .andExpect(jsonPath("$.lotId").value(to.toString()));
    }

    // ── patch ────────────────────────────────────────────────────────────────────

    @Test
    void patchHome_shouldAcceptTheWholeRecord_andKeepMoneyExact() throws Exception {
        String token = rootToken();
        UUID homeId = createHome(token, lotOn("HC08", "1"));

        mockMvc.perform(patch("/api/homes/" + homeId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "make": "Fleetwood",
                                  "model": "Broadmore",
                                  "modelYear": 1998,
                                  "bedroomCount": 3,
                                  "bathroomCount": 1.5,
                                  "width": 28,
                                  "length": 56,
                                  "sections": 2,
                                  "condition": "good",
                                  "estimatedValue": 42500.75,
                                  "estimatedValueOn": "2026-08-01",
                                  "parkOwned": true,
                                  "vin": "flw12345ab",
                                  "appearance": "Beige with brown trim",
                                  "note": "Roof recoated 2024",
                                  "parcel": "1234-567-890"
                                }
                                """))
                .andExpect(status().isOk())
                // lower case in, enum out
                .andExpect(jsonPath("$.condition").value("GOOD"))
                // a Double here would round; the service coerces to BigDecimal first
                .andExpect(jsonPath("$.estimatedValue").value(42500.75))
                .andExpect(jsonPath("$.estimatedValueOn").value("2026-08-01"))
                .andExpect(jsonPath("$.bathroomCount").value(1.5))
                .andExpect(jsonPath("$.parkOwned").value(true));
    }

    @Test
    void patchHome_shouldReturn400_forAnUnknownCondition() throws Exception {
        String token = rootToken();
        UUID homeId = createHome(token, lotOn("HC09", "1"));

        mockMvc.perform(patch("/api/homes/" + homeId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "condition": "MEDIOCRE" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchHome_shouldIgnoreFieldsItDoesNotKnow() throws Exception {
        // the controller whitelists, so an unknown key is dropped rather than refused
        String token = rootToken();
        UUID homeId = createHome(token, lotOn("HC10", "4B"));

        mockMvc.perform(patch("/api/homes/" + homeId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "ownerName": "Bob", "bogusColumn": 12 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mobile home on lot 4B"));
    }

    @Test
    void patchHome_shouldReturn404_whenNotFound() throws Exception {
        mockMvc.perform(patch("/api/homes/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + rootToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "make": "Fleetwood" }
                                """))
                .andExpect(status().isNotFound());
    }

    // ── the list filters ─────────────────────────────────────────────────────────

    @Test
    void listHomes_shouldFindByProperty() throws Exception {
        String token = rootToken();
        UUID propertyId = testData.insertProperty("HC11").uuid();
        createHome(token, testData.insertLot(propertyId, "1").uuid());
        createHome(token, testData.insertLot(propertyId, "2").uuid());

        mockMvc.perform(get("/api/homes")
                        .param("propertyCode", "HC11")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void listHomes_shouldFindByVin_ignoringCase() throws Exception {
        String token = rootToken();
        UUID homeId = createHome(token, lotOn("HC12", "1"));

        mockMvc.perform(patch("/api/homes/" + homeId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "vin": "flw12345ab" }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/homes")
                        .param("vin", "FLW12345AB")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(homeId.toString()));
    }

    @Test
    void listHomes_shouldReturn400_whenNoFilterGiven() throws Exception {
        mockMvc.perform(get("/api/homes")
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Give exactly one of property, lot or vin"));
    }

    @Test
    void listHomes_shouldReturn400_whenTwoFiltersGiven() throws Exception {
        mockMvc.perform(get("/api/homes")
                        .param("propertyCode", "HC13")
                        .param("vin", "anything")
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listHomes_shouldReturn200_andEmpty_forAnUnknownProperty() throws Exception {
        mockMvc.perform(get("/api/homes")
                        .param("propertyCode", "NOPE")
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── delete ───────────────────────────────────────────────────────────────────

    @Test
    void deleteHome_shouldReturn204_andThenTheHomeIsGoneEverywhere() throws Exception {
        String token = rootToken();
        UUID lotId = lotOn("HC14", "1");
        UUID homeId = createHome(token, lotId);

        mockMvc.perform(delete("/api/homes/" + homeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/homes/" + homeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/homes/" + homeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/homes")
                        .param("lotId", lotId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}

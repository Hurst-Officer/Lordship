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
public class StickBuiltControllerIT extends IntegrationTest {

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

    private UUID createStickBuilt(String token, UUID lotId) throws Exception {
        String body = mockMvc.perform(post("/api/stick-builts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(lotId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("uuid").asString());
    }

    // ── the auth boundary ────────────────────────────────────────────────────────

    @Test
    void createStickBuilt_shouldReturn401_whenNoToken() throws Exception {
        mockMvc.perform(post("/api/stick-builts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listStickBuilts_shouldReturn403_whenAgentLacksTheAuthority() throws Exception {
        String rootToken = rootToken();
        TestAuthSupport.TestAgent agent =
                TestAuthSupport.agentWithNoPermissions(mockMvc, objectMapper, rootToken);
        UUID lotId = lotOn("SC01", "1");

        // 403, not 401 -- the token is fine, the authority is missing
        mockMvc.perform(get("/api/stick-builts")
                        .param("lot", lotId.toString())
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void createStickBuilt_shouldReturn403_whenAgentHoldsOnlyView() throws Exception {
        String rootToken = rootToken();
        UUID lotId = lotOn("SC02", "1");
        TestAuthSupport.TestAgent agent = TestAuthSupport.agentWithPermissions(
                mockMvc, objectMapper, rootToken, "homes:view");

        mockMvc.perform(post("/api/stick-builts")
                        .header("Authorization", "Bearer " + agent.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(lotId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void listStickBuilts_shouldReturn200_whenAgentHoldsOnlyView() throws Exception {
        String rootToken = rootToken();
        UUID lotId = lotOn("SC03", "1");
        createStickBuilt(rootToken, lotId);

        TestAuthSupport.TestAgent agent = TestAuthSupport.agentWithPermissions(
                mockMvc, objectMapper, rootToken, "homes:view");

        mockMvc.perform(get("/api/stick-builts")
                        .param("lot", lotId.toString())
                        .header("Authorization", "Bearer " + agent.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── create ───────────────────────────────────────────────────────────────────

    @Test
    void createStickBuilt_shouldReturn201_withGeneratedNameAndDefaults() throws Exception {
        String token = rootToken();
        UUID lotId = lotOn("SC04", "4B");

        mockMvc.perform(post("/api/stick-builts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(lotId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uuid").exists())
                .andExpect(jsonPath("$.lotId").value(lotId.toString()))
                .andExpect(jsonPath("$.name").value("Building on lot 4B"))
                .andExpect(jsonPath("$.areaUnits").value("SQFT"))
                // TRUE here, the opposite of a mobile home
                .andExpect(jsonPath("$.parkOwned").value(true))
                .andExpect(jsonPath("$.createdBy").exists())
                .andExpect(jsonPath("$.structureType").doesNotExist())
                .andExpect(jsonPath("$.yearBuilt").doesNotExist());
    }

    @Test
    void createStickBuilt_shouldReturn400_whenLotIdMissing() throws Exception {
        mockMvc.perform(post("/api/stick-builts")
                        .header("Authorization", "Bearer " + rootToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createStickBuilt_shouldReturn400_whenLotDoesNotExist() throws Exception {
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(post("/api/stick-builts")
                        .header("Authorization", "Bearer " + rootToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(unknown)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No lot " + unknown));
    }

    // ── one structure per lot, as seen from the API ──────────────────────────────

    @Test
    void createStickBuilt_shouldReturn409_whenTheLotAlreadyHoldsAStickBuilt() throws Exception {
        String token = rootToken();
        UUID lotId = lotOn("SC05", "1");
        createStickBuilt(token, lotId);

        // 409, not 500: the request is well formed, the lot is taken
        mockMvc.perform(post("/api/stick-builts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(lotId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Lot " + lotId + " already holds a stick built"));
    }

    @Test
    void createStickBuilt_shouldReturn409_whenTheLotAlreadyHoldsAMobileHome() throws Exception {
        String token = rootToken();
        UUID lotId = lotOn("SC06", "1");

        mockMvc.perform(post("/api/homes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(lotId)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/stick-builts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(lotId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Lot " + lotId + " already holds a mobile home"));
    }

    @Test
    void createHome_shouldReturn409_whenTheLotAlreadyHoldsAStickBuilt() throws Exception {
        // the mirror direction, driven through the homes endpoint
        String token = rootToken();
        UUID lotId = lotOn("SC07", "1");
        createStickBuilt(token, lotId);

        mockMvc.perform(post("/api/homes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(lotId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Lot " + lotId + " already holds a stick built"));
    }

    // ── the generated name ───────────────────────────────────────────────────────

    @Test
    void patchStickBuilt_shouldUpgradeTheGeneratedName_whenStructureTypeArrives() throws Exception {
        String token = rootToken();
        UUID stickBuiltId = createStickBuilt(token, lotOn("SC08", "4B"));

        mockMvc.perform(patch("/api/stick-builts/" + stickBuiltId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "structureType": "single_family" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("House on lot 4B"))
                .andExpect(jsonPath("$.structureType").value("SINGLE_FAMILY"));
    }

    @Test
    void patchStickBuilt_shouldLeaveAHumanNamedStructureAlone() throws Exception {
        String token = rootToken();
        UUID stickBuiltId = createStickBuilt(token, lotOn("SC09", "4B"));

        mockMvc.perform(patch("/api/stick-builts/" + stickBuiltId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Manager House" }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/stick-builts/" + stickBuiltId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "structureType": "SHOP" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Manager House"));
    }

    @Test
    void patchStickBuilt_shouldFollowTheLot_whenMoved() throws Exception {
        String token = rootToken();
        UUID propertyId = testData.insertProperty("SC10").uuid();
        UUID from = testData.insertLot(propertyId, "4B").uuid();
        UUID to = testData.insertLot(propertyId, "9C").uuid();
        UUID stickBuiltId = createStickBuilt(token, from);

        mockMvc.perform(patch("/api/stick-builts/" + stickBuiltId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(to)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Building on lot 9C"))
                .andExpect(jsonPath("$.lotId").value(to.toString()));
    }

    // ── patch ────────────────────────────────────────────────────────────────────

    @Test
    void patchStickBuilt_shouldAcceptTheWholeRecord() throws Exception {
        String token = rootToken();
        UUID stickBuiltId = createStickBuilt(token, lotOn("SC11", "1"));

        mockMvc.perform(patch("/api/stick-builts/" + stickBuiltId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "structureType": "APARTMENT",
                                  "yearBuilt": 1974,
                                  "floor": 2,
                                  "bedroomCount": 2,
                                  "bathroomCount": 1.5,
                                  "area": 850.25,
                                  "areaUnits": "SQFT",
                                  "parkOwned": false,
                                  "appearance": "blue door, second floor balcony",
                                  "note": "roof recoated 2024"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.structureType").value("APARTMENT"))
                .andExpect(jsonPath("$.yearBuilt").value(1974))
                .andExpect(jsonPath("$.floor").value(2))
                // a Double here would round; the service coerces to BigDecimal first
                .andExpect(jsonPath("$.area").value(850.25))
                .andExpect(jsonPath("$.bathroomCount").value(1.5))
                .andExpect(jsonPath("$.parkOwned").value(false));
    }

    @Test
    void patchStickBuilt_shouldReturn400_forAnUnknownStructureType() throws Exception {
        String token = rootToken();
        UUID stickBuiltId = createStickBuilt(token, lotOn("SC12", "1"));

        mockMvc.perform(patch("/api/stick-builts/" + stickBuiltId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "structureType": "CASTLE" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchStickBuilt_shouldReturn400_whenAValueBreaksACheckConstraint() throws Exception {
        // area_units is not validated in the service, so this reaches Postgres. A bad
        // value is a 400, unlike the 409 a state conflict gets.
        String token = rootToken();
        UUID stickBuiltId = createStickBuilt(token, lotOn("SC13", "1"));

        mockMvc.perform(patch("/api/stick-builts/" + stickBuiltId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "areaUnits": "ACRES" }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchStickBuilt_shouldIgnoreFieldsItDoesNotKnow() throws Exception {
        String token = rootToken();
        UUID stickBuiltId = createStickBuilt(token, lotOn("SC14", "4B"));

        mockMvc.perform(patch("/api/stick-builts/" + stickBuiltId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "ownerName": "Bob", "bogusColumn": 12 }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Building on lot 4B"));
    }

    @Test
    void patchStickBuilt_shouldReturn404_whenNotFound() throws Exception {
        mockMvc.perform(patch("/api/stick-builts/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + rootToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "note": "x" }
                                """))
                .andExpect(status().isNotFound());
    }

    // ── the list filters ─────────────────────────────────────────────────────────

    @Test
    void listStickBuilts_shouldFindByProperty() throws Exception {
        String token = rootToken();
        UUID propertyId = testData.insertProperty("SC15").uuid();
        createStickBuilt(token, testData.insertLot(propertyId, "1").uuid());
        createStickBuilt(token, testData.insertLot(propertyId, "2").uuid());

        mockMvc.perform(get("/api/stick-builts")
                        .param("property", "SC15")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void listStickBuilts_shouldReturn400_whenNoFilterGiven() throws Exception {
        mockMvc.perform(get("/api/stick-builts")
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Give exactly one of property or lot"));
    }

    @Test
    void listStickBuilts_shouldReturn400_whenBothFiltersGiven() throws Exception {
        mockMvc.perform(get("/api/stick-builts")
                        .param("property", "SC16")
                        .param("lot", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + rootToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listStickBuilts_shouldReturn200_andEmpty_forALotWithNothingOnIt() throws Exception {
        String token = rootToken();

        mockMvc.perform(get("/api/stick-builts")
                        .param("lot", lotOn("SC17", "1").toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── delete ───────────────────────────────────────────────────────────────────

    @Test
    void deleteStickBuilt_shouldReturn204_andFreeTheLot() throws Exception {
        String token = rootToken();
        UUID lotId = lotOn("SC18", "1");
        UUID stickBuiltId = createStickBuilt(token, lotId);

        mockMvc.perform(delete("/api/stick-builts/" + stickBuiltId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/stick-builts/" + stickBuiltId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/stick-builts/" + stickBuiltId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        // the lot is free again -- soft delete releases it
        mockMvc.perform(post("/api/stick-builts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "lotId": "%s" }
                                """.formatted(lotId)))
                .andExpect(status().isCreated());
    }
}

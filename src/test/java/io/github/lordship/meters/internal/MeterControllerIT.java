package io.github.lordship.meters.internal;

import com.jayway.jsonpath.JsonPath;
import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.meters.MeterMeasurement;
import io.github.lordship.meters.MeterType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@Transactional
public class MeterControllerIT extends IntegrationTest {

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    MeterRepository meterRepository;

    private MeterRow buildRow(UUID lotId) {
        return MeterRow.forInsert(
                lotId,
                1.0,
                2.0,
                MeterType.WATER,
                MeterMeasurement.KWH,
                true,
                99999,
                1.0,
                15,
                false
        );
    }

    private UUID insertLot() {
        return testData.insertChainToLot().uuid();
    }

    // Two lots on one property, for the tests that link a parent meter to a child meter.
    private UUID[] insertTwoLots() {
        UUID propertyId = testData.insertProperty("TP").uuid();
        return new UUID[]{
                testData.insertLot(propertyId, "1").uuid(),
                testData.insertLot(propertyId, "2").uuid()
        };
    }

    private UUID createTestMeter(String token, UUID lotId) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/meters/create")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new MeterCreateRequest(lotId, 1.0, 1.0, MeterType.WATER, MeterMeasurement.GAL, true, 99999,                 1.0,
                                        15,
                                        false)))
                )
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.uuid"));
    }

    private UUID createChildMeter(String token, UUID lotId) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/meters/create")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(new MeterCreateRequest(lotId, 1.0, 1.0, MeterType.WATER, MeterMeasurement.GAL, false, 99999,                 1.0,
                                        15,
                                        false)))
                )
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.uuid"));
    }


    // REPOSITORY TESTS
    @Test
    void findAMeterById() {
        MeterRow saved = testData.insertChainToMeters();

        Optional<MeterRow> found = meterRepository.findById(saved.uuid());

        assertTrue(found.isPresent());
        assertEquals(saved.uuid(), found.get().uuid());
    }

    @Test
    void softDeleteRemovesFromTable() {
        UUID lotId = insertLot();
        MeterRow saved = meterRepository.save(buildRow(lotId));

        meterRepository.softDelete(saved.uuid());

        assertTrue(meterRepository.findById(saved.uuid()).isEmpty());
        assertTrue(meterRepository.findMeterByLot(saved.meterId()).isEmpty());
    }

    @Test
    void patchUpdatesAllowedFields() {
        UUID lotId = insertLot();
        MeterRow saved = meterRepository.save(buildRow(lotId));

        Map<String, Object> mutable = Map.of(
                "title", "Updated Title"
        );

        Optional<MeterRow> patched = meterRepository.patch(saved.uuid(), mutable);

        assertTrue(patched.isPresent());
        assertEquals("Updated Title", patched.get().title());
    }

    // CONTROLLER TESTS
    @Test
    void getMeter_shouldReturn401_whenNoTokenProvided() throws Exception {
        mockMvc.perform(get("/meters/{uuid}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createMeter_shouldReturn401_whenNoTokenProvided() throws Exception {
        var request = new MeterCreateRequest(UUID.randomUUID(), 1.0, 1.0, MeterType.WATER, MeterMeasurement.GAL, false, 99999,                 1.0,
                15,
                false);

        mockMvc.perform(post("/meters/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createMeter_shouldReturn400_whenMeterIdIsMissing() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        var invalidJson = """
                    { "meterId": null }
                """;

        mockMvc.perform(post("/meters/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }


    @Test
    void patchMeter_shouldReturn400_whenInvalidDateProvided() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID lotId = insertLot();

        UUID meterId = createTestMeter(token, lotId);

        // Invalid date
        mockMvc.perform(patch("/meters/{uuid}", meterId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installedAt\": \"not-a-date\"}"))
                .andExpect(status().isBadRequest());

        // Checks installedAt
        mockMvc.perform(patch("/meters/{uuid}", meterId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "installedAt": "not-a-date"
                        }
                    """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchMeter_shouldReturn200_whenValidDateProvided() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID lotId = insertLot();
        UUID meterUuid = createTestMeter(token, lotId);

        mockMvc.perform(patch("/meters/{uuid}", meterUuid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"installedAt\": \"2026-01-01\"}"))
                .andExpect(status().isOk())
                .andExpect(status().isOk());
    }

    @Test
    void recordRead_shouldReturn201_withCorrectFields() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        UUID lotId = insertLot();
        UUID meterUuid = createTestMeter(token, lotId);

        String body = """
            { "meterAmount": 1000.00, "readAt": "%s", "isEstimated": false, "rolloverCount": 0 }
            """.formatted(OffsetDateTime.now());

        mockMvc.perform(post("/meters/{uuid}/reads", meterUuid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void recordRead_shouldReturn404_whenMeterDoesNotExist() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        String body = """
            { "meterAmount": 1000, "readAt": "%s", "isEstimated": false, "rolloverCount": 0 }
            """.formatted(OffsetDateTime.now());

        mockMvc.perform(post("/meters/{uuid}/reads", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void linkMeters_shouldReturn201_whenParentIsMasterAndTypesMatch() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID[] lots = insertTwoLots();
        UUID lotId = lots[0];
        UUID lotId2 = lots[1];
        UUID parentUuid = createTestMeter(token, lotId);
        UUID childUuid = createTestMeter(token, lotId2);

        String body = String.format("""
            { "parentMeter": "%s", "childMeter": "%s", "hasUnmeteredRemainder": false, "effectiveFrom": "%s" }
            """, parentUuid, childUuid, LocalDate.now());

        mockMvc.perform(post("/meters/relationships")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void linkMeters_shouldReturn400_whenParentIsNotMasterMeter() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID[] lots = insertTwoLots();
        UUID lotId = lots[0];
        UUID lotId2 = lots[1];
        UUID notMaster = createChildMeter(token, lotId);
        UUID child = createChildMeter(token, lotId2);

        String body = String.format("""
            { "parentMeter": "%s", "childMeter": "%s", "hasUnmeteredRemainder": false, "effectiveFrom": "%s" }
            """, notMaster, child, LocalDate.now());

        mockMvc.perform(post("/meters/relationships")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resolveParentMeter_shouldReturn200_afterLinking() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID[] lots = insertTwoLots();
        UUID lotId = lots[0];
        UUID lotId2 = lots[1];
        UUID parentUuid = createTestMeter(token, lotId);
        UUID childUuid = createTestMeter(token, lotId2);

        String linkBody = String.format("""
            { "parentMeter": "%s", "childMeter": "%s", "hasUnmeteredRemainder": false, "effectiveFrom": "%s" }
            """, parentUuid, childUuid, LocalDate.now());
        mockMvc.perform(post("/meters/relationships")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(linkBody))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/meters/relationships/{childMeterId}/parent", childUuid)
                        .header("Authorization", "Bearer " + token)
                        .param("asOf", LocalDate.now().toString()))
                .andExpect(status().isOk());
    }

    @Test
    void resolveParentMeter_shouldReturn404_whenNoRelationshipExists() throws Exception {
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID lotId = insertLot();
        UUID childUuid = createTestMeter(token, lotId);

        mockMvc.perform(get("/meters/relationships/{childMeterId}/parent", childUuid)
                        .header("Authorization", "Bearer " + token)
                        .param("asOf", LocalDate.now().toString()))
                .andExpect(status().isNotFound());
    }
}
package io.github.lordship.tenancy.internal;

import com.jayway.jsonpath.JsonPath;
import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP behaviour only: routing, auth, status codes and the shape of what comes
 * back. The rules underneath -- the two-tenancy ceiling, the rentable guard and
 * the schema triggers that back both -- are exercised in
 * {@code TenancyRepositoryTest} and {@code TenancyServiceTests}; what is tested
 * here is that a refusal arrives as the right status with its reason intact.
 */
@Transactional
public class TenancyControllerIT extends IntegrationTest {

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

    private String token() throws Exception {
        return TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
    }

    private UUID lot(String propertyCode) {
        return testData.insertLot(testData.insertProperty(propertyCode).uuid(), "1").uuid();
    }

    private UUID createTenancy(UUID lotId) throws Exception {
        var json = """
                    { "lotId": "%s" }
                """.formatted(lotId);

        MvcResult createResult = mockMvc.perform(post("/api/tenancy/create")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(JsonPath.read(createResult.getResponse().getContentAsString(), "$.uuid"));
    }

    // Straight to the column: LotRow's compact constructor refuses to build a
    // not-rentable lot without a reason, and the lots module is not under test here.
    private void makeNotRentable(UUID lotId, String reason) {
        jdbc.sql("""
                        UPDATE lot
                           SET is_rentable = FALSE, not_rentable_reason = :reason
                         WHERE uuid = :uuid
                        """)
                .param("reason", reason)
                .param("uuid", lotId)
                .update();
    }

    // ---- create -------------------------------------------------------------

    @Test
    void createTenancy_shouldReturn201_withTheNewTenancy() throws Exception {
        // Arrange
        UUID lotId = lot("C001");

        // Act & Assert
        mockMvc.perform(post("/api/tenancy/create")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotId\": \"" + lotId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uuid").exists())
                .andExpect(jsonPath("$.lotId").value(lotId.toString()))
                .andExpect(jsonPath("$.endDate").doesNotExist());
    }

    @Test
    void createTenancy_shouldReturn401_whenNoTokenProvided() throws Exception {
        // Arrange
        var request = new TenancyController.TenancyCreateRequest(UUID.randomUUID());

        // Act & Assert
        mockMvc.perform(post("/api/tenancy/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createTenancy_shouldReturn400_whenLotIdIsMissing() throws Exception {
        // Arrange
        var invalidJson = """
                    { "lotId": null }
                """;

        // Act & Assert
        mockMvc.perform(post("/api/tenancy/create")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTenancy_shouldReturn404_whenLotDoesNotExist() throws Exception {
        // Act & Assert
        mockMvc.perform(post("/api/tenancy/create")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotId\": \"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    // The reason is the whole point of the refusal -- an empty 409 tells the
    // office worker nothing about why the lot is off limits.
    @Test
    void createTenancy_shouldReturn409_withTheReason_whenLotIsNotRentable() throws Exception {
        // Arrange
        UUID lotId = lot("C002");
        makeNotRentable(lotId, "condemned after the flood");

        // Act & Assert
        mockMvc.perform(post("/api/tenancy/create")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotId\": \"" + lotId + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(Matchers.containsString("condemned after the flood")));
    }

    @Test
    void createTenancy_shouldReturn409_whenLotAlreadyHasTwoActive() throws Exception {
        // Arrange
        UUID lotId = lot("C003");
        testData.insertTenancy(lotId);
        testData.insertTenancy(lotId);

        // Act & Assert
        mockMvc.perform(post("/api/tenancy/create")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotId\": \"" + lotId + "\"}"))
                .andExpect(status().isConflict());
    }

    // ---- read ---------------------------------------------------------------

    @Test
    void getTenancy_shouldReturnTheTenancy() throws Exception {
        // Arrange
        UUID lotId = lot("C004");
        UUID tenancyId = createTenancy(lotId);

        // Act & Assert
        mockMvc.perform(get("/api/tenancy/{uuid}", tenancyId)
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uuid").value(tenancyId.toString()))
                .andExpect(jsonPath("$.lotId").value(lotId.toString()));
    }

    @Test
    void getTenancy_shouldReturn404_whenUuidIsUnknown() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/tenancy/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getTenancy_shouldReturn401_whenNoTokenProvided() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/tenancy/{uuid}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getActiveTenanciesByLot_shouldReturnOnlyActiveTenancies() throws Exception {
        // Arrange
        UUID lotId = lot("C005");

        // Create a closed tenancy
        UUID closedTenancy = testData.insertTenancy(lotId).uuid();

        // Close tenancy before creating a second
        mockMvc.perform(patch("/api/tenancy/{uuid}", closedTenancy)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"" + LocalDate.now() + "\"}"))
                .andExpect(status().isOk());

        // Create the active tenancy
        testData.insertTenancy(lotId);

        // Act & Assert
        mockMvc.perform(get("/api/tenancy/lot/{lotId}", lotId)
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].endDate").doesNotExist());
    }

    // ---- patch --------------------------------------------------------------

    @Test
    void patchTenancy_shouldCloseTenancy_afterSettingEndDate() throws Exception {
        // Arrange
        UUID tenancyId = createTenancy(lot("C006"));
        LocalDate endDate = LocalDate.now();

        // Act & Assert
        mockMvc.perform(patch("/api/tenancy/{uuid}", tenancyId)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"" + endDate + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endDate").value(endDate.toString()));

        // Assert: the change persisted
        mockMvc.perform(get("/api/tenancy/{uuid}", tenancyId)
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endDate").value(endDate.toString()));
    }

    // Every field the endpoint accepts, checked against the tenancy table itself
    // rather than the response shape, so a JSON key mapped to a column that does
    // not exist (or the wrong one) fails here.
    @Test
    void patchTenancy_shouldUpdateEveryEditableColumn() throws Exception {
        // Arrange
        UUID tenancyId = createTenancy(lot("C012"));
        LocalDate start = LocalDate.now().minusDays(30);
        LocalDate end = LocalDate.now();
        String body = """
                {
                  "startDate": "%s",
                  "endDate": "%s",
                  "notes": "Called re: late rent",
                  "noPartialPayments": true,
                  "noPersonalChecks": true,
                  "acceptPayments": false,
                  "exemptFromLateFees": true
                }
                """.formatted(start, end);

        // Act
        mockMvc.perform(patch("/api/tenancy/{uuid}", tenancyId)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Assert
        Map<String, Object> saved = jdbc.sql("""
                        SELECT start_date, end_date, notes, no_partial_payments,
                               no_personal_checks, accept_payments, exempt_from_late_fees
                          FROM tenancy
                         WHERE uuid = :uuid
                        """)
                .param("uuid", tenancyId)
                .query()
                .singleRow();

        // Dates compared as ISO strings so the assertion does not depend on which
        // java.sql/java.time type the driver hands back for a DATE column.
        assertEquals(start.toString(), String.valueOf(saved.get("start_date")));
        assertEquals(end.toString(), String.valueOf(saved.get("end_date")));
        assertEquals("Called re: late rent", saved.get("notes"));
        assertEquals(true, saved.get("no_partial_payments"));
        assertEquals(true, saved.get("no_personal_checks"));
        assertEquals(false, saved.get("accept_payments"));
        assertEquals(true, saved.get("exempt_from_late_fees"));
    }

    @Test
    void patchTenancy_shouldReturn404_whenUuidIsUnknown() throws Exception {
        // Act & Assert
        mockMvc.perform(patch("/api/tenancy/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"" + LocalDate.now() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchTenancy_shouldReturn400_whenInvalidDateProvided() throws Exception {
        // Arrange
        UUID tenancyId = testData.insertTenancy(lot("C007")).uuid();
        String token = token();

        // Act & Assert: invalid endDate
        mockMvc.perform(patch("/api/tenancy/{uuid}", tenancyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"not-a-date\"}"))
                .andExpect(status().isBadRequest());

        // Act & Assert: invalid startDate
        mockMvc.perform(patch("/api/tenancy/{uuid}", tenancyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\": \"not-a-date\"}"))
                .andExpect(status().isBadRequest());

        // Act & Assert: both invalid
        mockMvc.perform(patch("/api/tenancy/{uuid}", tenancyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "startDate": "not-a-date",
                            "endDate": "also-not-a-date"
                        }
                    """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchTenancy_shouldReturn400_whenEndDateIsBeforeStartDate() throws Exception {
        // Arrange
        UUID tenancyId = createTenancy(lot("C008"));
        String token = token();

        mockMvc.perform(patch("/api/tenancy/{uuid}", tenancyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\": \"" + LocalDate.now() + "\"}"))
                .andExpect(status().isOk());

        // Act & Assert
        mockMvc.perform(patch("/api/tenancy/{uuid}", tenancyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"" + LocalDate.now().minusDays(5) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    // Ending a tenancy is setting its endDate, so clearing it is how a
    // mistakenly closed tenancy comes back.
    @Test
    void patchTenancy_shouldReopenTenancy_whenLotHasRoom() throws Exception {
        // Arrange
        UUID tenancyId = createTenancy(lot("C009"));
        String token = token();

        mockMvc.perform(patch("/api/tenancy/{uuid}", tenancyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"" + LocalDate.now() + "\"}"))
                .andExpect(status().isOk());

        // Act & Assert
        mockMvc.perform(patch("/api/tenancy/{uuid}", tenancyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endDate").doesNotExist());
    }

    @Test
    void patchTenancy_shouldReturn409_whenReopeningOntoAFullLot() throws Exception {
        // Arrange
        UUID lotId = lot("C010");
        String token = token();

        UUID first = createTenancy(lotId);
        mockMvc.perform(patch("/api/tenancy/{uuid}", first)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"" + LocalDate.now() + "\"}"))
                .andExpect(status().isOk());

        // Two live ones now stand between the closed tenancy and its old slot
        testData.insertTenancy(lotId);
        testData.insertTenancy(lotId);

        // Act & Assert
        mockMvc.perform(patch("/api/tenancy/{uuid}", first)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": null}"))
                .andExpect(status().isConflict());
    }

    @Test
    void patchTenancy_shouldReturn401_whenNoTokenProvided() throws Exception {
        // Act & Assert
        mockMvc.perform(patch("/api/tenancy/{uuid}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"" + LocalDate.now() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- delete -------------------------------------------------------------

    @Test
    void deleteTenancy_shouldReturn204_thenReturn404() throws Exception {
        // Arrange
        UUID tenancyId = createTenancy(lot("C011"));
        String token = token();

        // Act & Assert: the first delete removes it
        mockMvc.perform(delete("/api/tenancy/{uuid}", tenancyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // Act & Assert: the second call changed no rows, so the service reports nothing deleted
        mockMvc.perform(delete("/api/tenancy/{uuid}", tenancyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        // Assert: it is gone from reads
        mockMvc.perform(get("/api/tenancy/{uuid}", tenancyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteTenancy_shouldReturn401_whenNoTokenProvided() throws Exception {
        // Act & Assert
        mockMvc.perform(delete("/api/tenancy/{uuid}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
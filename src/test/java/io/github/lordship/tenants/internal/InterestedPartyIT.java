package io.github.lordship.tenants.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@Transactional
public class InterestedPartyIT extends IntegrationTest {

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    private String token() throws Exception {
        return TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
    }

    // Fixtures go through repositories, not services: a service call pulls in the
    // audit write, which has no principal to attribute to outside a request.
    private UUID tenancy(String propertyCode) {
        return testData.insertTenancy(
                testData.insertLot(testData.insertProperty(propertyCode).uuid(), "1").uuid()
        ).uuid();
    }

    private UUID person(String name) {
        return testData.insertPerson(name).uuid();
    }

    // NOTE: assumes TestData has (or grows) an insertInterestedParty(tenancyId, personId,
    // startDate) fixture helper mirroring insertTenant. Add it there if it doesn't exist yet.
    private UUID interestedParty(UUID tenancyId, UUID personId, LocalDate startDate) {
        return testData.insertInterestedParty(tenancyId, personId, startDate).uuid();
    }

    private String body(UUID tenancyId, UUID personId, String startDate) {
        return startDate == null
                ? """
                  { "tenancyId": "%s", "personId": "%s" }
                  """.formatted(tenancyId, personId)
                : """
                  { "tenancyId": "%s", "personId": "%s", "startDate": "%s" }
                  """.formatted(tenancyId, personId, startDate);
    }

    // ---- auth ---------------------------------------------------------------

    @Test
    void createInterestedParty_shouldReturn401_whenNoTokenProvided() throws Exception {
        mockMvc.perform(post("/api/interested-party/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), UUID.randomUUID(), null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getById_shouldReturn401_whenNoTokenProvided() throws Exception {
        mockMvc.perform(get("/api/interested-party/{uuid}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // A token that does not parse is a credential problem, not a permission one,
    // and must not read as a missing authority.
    @Test
    void getById_shouldReturn401_whenTheTokenIsMalformed() throws Exception {
        mockMvc.perform(get("/api/interested-party/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        Matchers.containsString("invalid_token")));
    }

    @Test
    void getById_shouldReturn401_whenTheSchemeIsNotBearer() throws Exception {
        mockMvc.perform(get("/api/interested-party/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Basic abc123"))
                .andExpect(status().isUnauthorized());
    }

    // ---- create -------------------------------------------------------------

    @Test
    void createInterestedParty_shouldReturn400_whenTheBodyIsIncomplete() throws Exception {
        mockMvc.perform(post("/api/interested-party/create")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "tenancyId": null, "personId": null }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createInterestedParty_shouldReturn404_whenTheTenancyIsUnknown() throws Exception {
        mockMvc.perform(post("/api/interested-party/create")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), person("Bank of Springfield"), null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void createInterestedParty_shouldReturn201_andUseTheSuppliedStartDate() throws Exception {
        UUID tenancyId = tenancy("C201");

        mockMvc.perform(post("/api/interested-party/create")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tenancyId, person("Bank of Springfield"), "2026-10-01")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uuid").exists())
                .andExpect(jsonPath("$.tenancyId").value(tenancyId.toString()))
                .andExpect(jsonPath("$.startDate").value("2026-10-01"))
                .andExpect(jsonPath("$.endDate").doesNotExist());
    }

    @Test
    void createInterestedParty_shouldFillInAStartDate_whenTheCallerOmitsIt() throws Exception {
        UUID tenancyId = tenancy("C202");

        mockMvc.perform(post("/api/interested-party/create")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tenancyId, person("Bank of Springfield"), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startDate").exists());
    }

    @Test
    void createInterestedParty_shouldLeaveExistingInterestedPartiesActive() throws Exception {
        UUID tenancyId = tenancy("C203");
        String token = token();

        mockMvc.perform(post("/api/interested-party/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tenancyId, person("Bank of Springfield"), "2026-01-01")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/interested-party/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tenancyId, person("Credit Union of Shelbyville"), "2026-03-01")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/interested-party/tenancy/{tenancyId}", tenancyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].endDate").doesNotExist())
                .andExpect(jsonPath("$[1].endDate").doesNotExist());
    }

    // ---- reads ----------------------------------------------------------------

    @Test
    void getById_shouldReturn404_whenTheInterestedPartyDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/interested-party/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByTenancy_shouldReturnConcludedParties_whenActiveOnlyIsFalse() throws Exception {
        UUID tenancyId = tenancy("C204");
        UUID interestedPartyId = interestedParty(tenancyId, person("Bank of Springfield"),
                LocalDate.of(2024, 1, 1));
        String token = token();

        // Conclude it, then confirm the two views differ by exactly that row.
        mockMvc.perform(patch("/api/interested-party/{uuid}", interestedPartyId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "endDate": "2025-06-30" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endDate").value("2025-06-30"));

        mockMvc.perform(get("/api/interested-party/tenancy/{tenancyId}", tenancyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/interested-party/tenancy/{tenancyId}", tenancyId)
                        .param("activeOnly", "false")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void getByPerson_shouldReturnTheirInterestedPartyRecords() throws Exception {
        UUID personId = person("Bank of Springfield");
        UUID tenancyId = tenancy("C205");
        interestedParty(tenancyId, personId, LocalDate.of(2026, 1, 1));

        mockMvc.perform(get("/api/interested-party/person/{personId}", personId)
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].personId").value(personId.toString()));
    }

    // ---- patch ------------------------------------------------------------------

    @Test
    void patchInterestedParty_shouldReturn400_whenEndDateIsBeforeStartDate() throws Exception {
        UUID tenancyId = tenancy("C206");
        UUID interestedPartyId = interestedParty(tenancyId, person("Bank of Springfield"),
                LocalDate.of(2026, 6, 1));

        mockMvc.perform(patch("/api/interested-party/{uuid}", interestedPartyId)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "endDate": "2026-01-01" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void patchInterestedParty_shouldReturn404_whenTheInterestedPartyDoesNotExist() throws Exception {
        mockMvc.perform(patch("/api/interested-party/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "endDate": "2026-01-01" }
                                """))
                .andExpect(status().isNotFound());
    }

    // Regression test for the ALLOWED_COLUMNS bug: "notification_reason," (trailing
    // comma folded into the string) meant the real column name "notification_reason"
    // was never a member of the allowed set, so this patch could never go through.
    // This test fails against the buggy set and passes once the comma is removed.
    @Test
    void patchInterestedParty_shouldUpdateNotificationReason() throws Exception {
        UUID tenancyId = tenancy("C207");
        UUID interestedPartyId = interestedParty(tenancyId, person("Bank of Springfield"),
                LocalDate.of(2026, 1, 1));

        mockMvc.perform(patch("/api/interested-party/{uuid}", interestedPartyId)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "notificationReason": "Home listed as collateral" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationReason").value("Home listed as collateral"));
    }

    @Test
    void patchInterestedParty_shouldUpdateAcceptPaymentsAndNotes() throws Exception {
        UUID tenancyId = tenancy("C208");
        UUID interestedPartyId = interestedParty(tenancyId, person("Bank of Springfield"),
                LocalDate.of(2026, 1, 1));

        mockMvc.perform(patch("/api/interested-party/{uuid}", interestedPartyId)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "acceptPayments": false, "notes": "Flagged during default review" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptPayments").value(false))
                .andExpect(jsonPath("$.notes").value("Flagged during default review"));
    }

    // ---- delete -------------------------------------------------------------------

    @Test
    void deleteInterestedParty_shouldReturn204_thenNotFound() throws Exception {
        UUID tenancyId = tenancy("C209");
        UUID interestedPartyId = interestedParty(tenancyId, person("Bank of Springfield"),
                LocalDate.of(2026, 1, 1));
        String token = token();

        mockMvc.perform(delete("/api/interested-party/{uuid}", interestedPartyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/interested-party/{uuid}", interestedPartyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}

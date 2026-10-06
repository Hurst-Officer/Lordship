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
public class TenantControllerIT extends IntegrationTest {

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
    void createTenant_shouldReturn401_whenNoTokenProvided() throws Exception {
        mockMvc.perform(post("/api/tenants/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), UUID.randomUUID(), null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTenant_shouldReturn401_whenNoTokenProvided() throws Exception {
        mockMvc.perform(get("/api/tenants/{uuid}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // A token that does not parse is a credential problem, not a permission one,
    // and must not read as a missing authority.
    @Test
    void getTenant_shouldReturn401_whenTheTokenIsMalformed() throws Exception {
        mockMvc.perform(get("/api/tenants/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        Matchers.containsString("invalid_token")));
    }

    @Test
    void getTenant_shouldReturn401_whenTheSchemeIsNotBearer() throws Exception {
        mockMvc.perform(get("/api/tenants/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Basic abc123"))
                .andExpect(status().isUnauthorized());
    }

    // ---- create -------------------------------------------------------------

    @Test
    void createTenant_shouldReturn400_whenTheBodyIsIncomplete() throws Exception {
        mockMvc.perform(post("/api/tenants/create")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "tenancyId": null, "personId": null }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createTenant_shouldReturn404_whenTheTenancyIsUnknown() throws Exception {
        mockMvc.perform(post("/api/tenants/create")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), person("Jim Halpert"), null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void createTenant_shouldReturn201_andUseTheSuppliedStartDate() throws Exception {
        UUID tenancyId = tenancy("C101");

        mockMvc.perform(post("/api/tenants/create")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tenancyId, person("Jim Halpert"), "2026-10-01")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.tenancyId").value(tenancyId.toString()))
                .andExpect(jsonPath("$.startDate").value("2026-10-01"))
                .andExpect(jsonPath("$.endDate").doesNotExist());
    }

    @Test
    void createTenant_shouldFillInAStartDate_whenTheCallerOmitsIt() throws Exception {
        UUID tenancyId = tenancy("C102");

        mockMvc.perform(post("/api/tenants/create")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tenancyId, person("Jim Halpert"), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startDate").exists());
    }

    // The bug this rewrite exists for. Adding Pam used to end Jim's row, so a
    // two-person household could not exist.
    @Test
    void createTenant_shouldLeaveTheExistingTenantsActive() throws Exception {
        UUID tenancyId = tenancy("C103");
        String token = token();

        mockMvc.perform(post("/api/tenants/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tenancyId, person("Jim Halpert"), "2026-01-01")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/tenants/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tenancyId, person("Pam Beesly"), "2026-03-01")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/tenants/tenancy/{tenancyId}", tenancyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].endDate").doesNotExist())
                .andExpect(jsonPath("$[1].endDate").doesNotExist());
    }

    @Test
    void createTenant_shouldReturn409_whenThatPersonIsAlreadyOnTheTenancy() throws Exception {
        UUID tenancyId = tenancy("C104");
        UUID personId = person("Jim Halpert");
        String token = token();

        mockMvc.perform(post("/api/tenants/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tenancyId, personId, "2026-01-01")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/tenants/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tenancyId, personId, "2026-05-01")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }

    // ---- reads --------------------------------------------------------------

    @Test
    void getTenant_shouldReturn404_whenTheTenantDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/tenants/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByTenancy_shouldReturnEndedStays_whenActiveOnlyIsFalse() throws Exception {
        UUID tenancyId = tenancy("C105");
        UUID tenantId = testData.insertTenant(tenancyId, person("Jim Halpert"),
                LocalDate.of(2024, 1, 1)).uuid();
        String token = token();

        // Move them out, then confirm the two views differ by exactly that row.
        mockMvc.perform(patch("/api/tenants/{uuid}", tenantId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "endDate": "2025-06-30" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endDate").value("2025-06-30"));

        mockMvc.perform(get("/api/tenants/tenancy/{tenancyId}", tenancyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/tenants/tenancy/{tenancyId}", tenancyId)
                        .param("activeOnly", "false")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ---- patch and delete ---------------------------------------------------

    @Test
    void patchTenant_shouldReturn400_whenEndDateIsBeforeStartDate() throws Exception {
        UUID tenancyId = tenancy("C106");
        UUID tenantId = testData.insertTenant(tenancyId, person("Jim Halpert"),
                LocalDate.of(2026, 6, 1)).uuid();

        mockMvc.perform(patch("/api/tenants/{uuid}", tenantId)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "endDate": "2026-01-01" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void patchTenant_shouldReturn404_whenTheTenantDoesNotExist() throws Exception {
        mockMvc.perform(patch("/api/tenants/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "endDate": "2026-01-01" }
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteTenant_shouldReturn204_thenNotFound() throws Exception {
        UUID tenancyId = tenancy("C107");
        UUID tenantId = testData.insertTenant(tenancyId, person("Jim Halpert"),
                LocalDate.of(2026, 1, 1)).uuid();
        String token = token();

        mockMvc.perform(delete("/api/tenants/{uuid}", tenantId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/tenants/{uuid}", tenantId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
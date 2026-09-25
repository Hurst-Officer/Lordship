package io.github.lordship.instruments.internal;

import com.jayway.jsonpath.JsonPath;
import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.lots.internal.LotRow;
import io.github.lordship.properties.internal.PropertyRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creating a draft document and writing its rent schedule.
 *
 * <p>The draft comes first. Its charge terms are then created from it, already
 * linked to it through source_uuid.
 *
 * <p>An IT rather than a service test because every step writes an audit row,
 * and an audit row needs a logged-in agent.
 */
@Transactional
public class InstrumentDraftIT extends IntegrationTest {

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

    private String token;
    private UUID property;
    private UUID tenancy;

    @BeforeEach
    void setUp() throws Exception {
        token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        PropertyRow propertyRow = testData.insertProperty("DR");
        LotRow lotRow = testData.insertLot(propertyRow.uuid(), "3");
        property = propertyRow.uuid();
        tenancy = testData.insertTenancy(lotRow.uuid()).uuid();

        permitLand(lotRow.uuid());
        copyLandTermsToProperty();
    }

    // ---- creating the draft --------------------------------------------------

    @Test
    void createDraft_shouldSaveTheAgreementType() throws Exception {
        // Act / Assert
        createDraft("LEASE")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.agreementType").value("LAND"));
    }

    @Test
    void createDraft_shouldStartAFirstLeaseOnTheTenancysStartDate() throws Exception {
        // Arrange
        setTenancyStart("2026-11-01");

        // Act / Assert
        createDraft("LEASE")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.termStart").value("2026-11-01"));
    }

    @Test
    void createDraft_shouldStartARenewalTheDayAfterTheCurrentLeaseEnds() throws Exception {
        // Arrange -- the first lease runs 2026-11-01 for 24 months, last day 2028-10-31
        setTenancyStart("2026-11-01");
        leaseDraftWithDates();

        // Act / Assert -- a new lease must not cut the current one short
        createDraft("LEASE")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.termStart").value("2028-11-01"));
    }

    @Test
    void createDraft_shouldSkipAnAbandonedLease_whenPickingTheStart() throws Exception {
        // Arrange
        setTenancyStart("2026-11-01");
        abandon(leaseDraftWithDates());

        // Act / Assert
        createDraft("LEASE")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.termStart").value("2026-11-01"));
    }

    @Test
    void createDraft_shouldLeaveTheStartBlank_onAnIncreaseNotice() throws Exception {
        // Arrange
        setTenancyStart("2026-11-01");

        // Act / Assert
        createDraft("INCREASE_NOTICE")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.termStart").doesNotExist());
    }

    @Test
    void createDraft_shouldReturn400_withoutAnAgreementType() throws Exception {
        // Act / Assert
        mockMvc.perform(post("/api/instruments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "tenancyId": "%s", "type": "LEASE" }
                                """.formatted(tenancy)))
                .andExpect(status().isBadRequest());
    }

    // ---- previewing the schedule ---------------------------------------------

    @Test
    void previewSchedule_shouldStartOnTheDraftsStartDate_atTheLotsRate() throws Exception {
        // Arrange
        UUID lease = leaseDraftWithDates();

        // Act / Assert -- the template has no escalation, so there is one step
        mockMvc.perform(get("/api/instruments/{uuid}/charge-terms/schedule", lease)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].validAt").value("2026-11-01"))
                .andExpect(jsonPath("$[0].rate").value(650.00));
    }

    @Test
    void previewSchedule_shouldReturn400_whenTheStartDateIsNotSet() throws Exception {
        // Arrange
        UUID lease = idOf(createDraft("LEASE"));

        // Act / Assert
        mockMvc.perform(get("/api/instruments/{uuid}/charge-terms/schedule", lease)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("instrument.term_start_needed"))
                .andExpect(jsonPath("$.field").value("termStart"));
    }

    // ---- writing the schedule ------------------------------------------------

    @Test
    void writeSchedule_shouldLinkEveryStepToTheDocument() throws Exception {
        // Arrange
        UUID lease = leaseDraftWithDates();

        // Act
        writeTwoSteps(lease).andExpect(status().isCreated());

        // Assert
        chargeTermsOf(lease)
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].sourceUuid", everyItem(is(lease.toString()))))
                .andExpect(jsonPath("$[*].source", everyItem(is("LEASE"))))
                .andExpect(jsonPath("$[*].status", everyItem(is("PROPOSED"))));
    }

    @Test
    void writeSchedule_shouldGiveAnIncreaseNoticeItsOwnKindOfTerm() throws Exception {
        // Arrange -- a notice has no months, so its one step is posted directly
        UUID notice = idOf(createDraft("INCREASE_NOTICE"));

        // Act / Assert
        writeOneStep(notice, "2027-01-01", "700.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].source").value("INCREASE_NOTICE"));
    }

    @Test
    void writeSchedule_shouldReplaceTheSteps_andKeepTheFeesAlreadySet() throws Exception {
        // Arrange -- one step, the pet fee set to 60, then the schedule is rebuilt
        UUID lease = leaseDraftWithDates();
        UUID firstTerm = uuidAt(writeOneStep(lease, "2026-11-01", "650.00").andReturn(), "$[0].uuid");
        patchTerm(firstTerm, """
                { "petFee": 60.00 }
                """);

        // Act
        writeTwoSteps(lease).andExpect(status().isCreated());

        // Assert
        chargeTermsOf(lease)
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].petFee", everyItem(is(60.0))));
    }

    @Test
    void writeSchedule_shouldReturn400_forAWaiver() throws Exception {
        // Arrange
        UUID waiver = idOf(createDraft("WAIVER"));

        // Act / Assert
        writeOneStep(waiver, "2026-11-01", "650.00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("instrument.takes_no_charge_terms"));
    }

    @Test
    void writeSchedule_shouldReturn409_onceTheDocumentIsNoLongerADraft() throws Exception {
        // Arrange
        UUID lease = leaseDraftWithDates();
        abandon(lease);

        // Act / Assert
        writeOneStep(lease, "2026-11-01", "650.00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("instrument.not_editable"));
    }

    // ---- editing fees across the schedule -------------------------------------

    @Test
    void patchChargeTerm_shouldCopyAFeeChangeToEveryStep_butNotTheRent() throws Exception {
        // Arrange
        UUID lease = leaseDraftWithDates();
        UUID firstTerm = uuidAt(writeTwoSteps(lease).andReturn(), "$[0].uuid");

        // Act
        patchTerm(firstTerm, """
                { "petFee": 55.00, "rate": 640.00 }
                """);

        // Assert
        chargeTermsOf(lease)
                .andExpect(jsonPath("$[*].petFee", everyItem(is(55.0))))
                .andExpect(jsonPath("$[0].rate").value(640.00))
                .andExpect(jsonPath("$[1].rate").value(676.00));
    }

    // ---- abandoning ----------------------------------------------------------

    @Test
    void abandon_shouldDeleteTheDocumentsChargeTerms() throws Exception {
        // Arrange
        UUID lease = leaseDraftWithDates();
        writeTwoSteps(lease).andExpect(status().isCreated());

        // Act
        abandon(lease);

        // Assert
        chargeTermsOf(lease).andExpect(jsonPath("$", hasSize(0)));
    }

    // ---- Helpers -------------------------------------------------------------

    private ResultActions createDraft(String type) throws Exception {
        return mockMvc.perform(post("/api/instruments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "tenancyId": "%s", "type": "%s", "agreementType": "LAND" }
                        """.formatted(tenancy, type)));
    }

    /** A LEASE draft starting 2026-11-01 for 24 months. */
    private UUID leaseDraftWithDates() throws Exception {
        UUID lease = idOf(createDraft("LEASE"));
        mockMvc.perform(patch("/api/instruments/{uuid}", lease)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "termStart": "2026-11-01", "termMonths": 24, "onExpiry": "MONTH_TO_MONTH" }
                                """))
                .andExpect(status().isOk());
        return lease;
    }

    private ResultActions writeOneStep(UUID document, String validAt, String rate) throws Exception {
        return mockMvc.perform(post("/api/instruments/{uuid}/charge-terms/schedule", document)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "steps": [ { "validAt": "%s", "rate": %s } ] }
                        """.formatted(validAt, rate)));
    }

    private ResultActions writeTwoSteps(UUID document) throws Exception {
        return mockMvc.perform(post("/api/instruments/{uuid}/charge-terms/schedule", document)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "steps": [
                            { "validAt": "2026-11-01", "rate": 650.00 },
                            { "validAt": "2027-11-01", "rate": 676.00 }
                          ]
                        }
                        """));
    }

    private ResultActions chargeTermsOf(UUID document) throws Exception {
        return mockMvc.perform(get("/api/instruments/{uuid}/charge-terms", document)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void patchTerm(UUID term, String body) throws Exception {
        mockMvc.perform(patch("/api/tenancy-charge-terms/{uuid}", term)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void abandon(UUID document) throws Exception {
        mockMvc.perform(post("/api/instruments/{uuid}/abandon", document)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void copyLandTermsToProperty() throws Exception {
        MvcResult global = mockMvc.perform(post("/api/terms-templates/global")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "DR Land Terms", "agreementType": "LAND" }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        mockMvc.perform(post("/api/terms-templates/{uuid}/copy", uuidAt(global, "$.uuid"))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "propertyId": "%s" }
                                """.formatted(property)))
                .andExpect(status().isCreated());
    }

    private void setTenancyStart(String date) {
        jdbc.sql("UPDATE tenancy SET start_date = CAST(:date AS DATE) WHERE uuid = :uuid")
                .param("date", date)
                .param("uuid", tenancy)
                .update();
    }

    private void permitLand(UUID lotId) {
        jdbc.sql("""
                        INSERT INTO lot_permissible_agreement_type (lot_id, agreement_type, target_rate, asking_rate)
                        VALUES (:lotId, 'LAND'::agreement_type, :rate, :rate)
                        """)
                .param("lotId", lotId)
                .param("rate", new BigDecimal("650.00"))
                .update();
    }

    private UUID idOf(ResultActions created) throws Exception {
        return uuidAt(created.andExpect(status().isCreated()).andReturn(), "$.uuid");
    }

    private static UUID uuidAt(MvcResult result, String path) throws Exception {
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), path));
    }
}

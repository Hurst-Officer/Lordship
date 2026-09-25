package io.github.lordship.tenancyterms.internal;

import com.jayway.jsonpath.JsonPath;
import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.lots.internal.LotRow;
import io.github.lordship.properties.internal.PropertyRow;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.tenancy.internal.TenancyRow;
import io.github.lordship.tenancyterms.TenancyChargeTerm;
import io.github.lordship.tenancyterms.TenancyChargeTermService;
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

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
public class TenancyChargeTermControllerIT extends IntegrationTest {

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TenancyChargeTermService tenancyChargeTermService;

    @Autowired
    JdbcClient jdbc;

    private String token;
    private UUID property;
    private UUID lot;
    private UUID tenancy;

    @BeforeEach
    void setUp() throws Exception {
        token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        PropertyRow propertyRow = testData.insertProperty("IT");
        LotRow lotRow = testData.insertLot(propertyRow.uuid(), "1");
        TenancyRow tenancyRow = testData.insertTenancy(lotRow.uuid());

        property = propertyRow.uuid();
        lot = lotRow.uuid();
        tenancy = tenancyRow.uuid();

        permitAgreementType(lot, AgreementType.LAND, null);
        copyTemplateToProperty("IT CT Land Terms", "LAND");
    }

    // ---- create --------------------------------------------------------------

    @Test
    void createChargeTerm_shouldReturn201_andCopyTheTemplate() throws Exception {
        // Act
        MvcResult result = createTerm("LAND", "2026-09-01", "MIGRATION")
                // Assert
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uuid").exists())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.agreementType").value("LAND"))
                .andExpect(jsonPath("$.termsTemplate").exists())
                .andExpect(jsonPath("$.sourceUuid").doesNotExist())
                .andReturn();

        // The values came from the template, not from Java nulls
        TenancyChargeTerm created = fetch(result);
        assertEquals(2, created.allowedCars());
        assertEquals(4, created.carsMax());
        assertEquals(1, created.paymentDueDay());
        assertEquals(7, created.gracePeriodDays());
    }

    @Test
    void createChargeTerm_shouldReturn400_forATermThatNeedsADocument() throws Exception {
        // Act / Assert -- LEASE terms are created from their document
        createTerm("LAND", "2026-09-01", "LEASE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("term.needs_a_document"))
                .andExpect(jsonPath("$.field").value("source"));
    }

    @Test
    void createChargeTerm_shouldReturn400_forACorrectionWithoutAReason() throws Exception {
        // Act / Assert
        createTerm("LAND", "2026-09-01", "CORRECTION")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("term.correction_needs_reason"))
                .andExpect(jsonPath("$.field").value("correctionReason"));
    }

    @Test
    void createChargeTerm_shouldSaveTheReason_forACorrection() throws Exception {
        // Act / Assert
        createTerm("LAND", "2026-09-01", "CORRECTION", "found an unscanned lease")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("CORRECTION"))
                .andExpect(jsonPath("$.correctionReason").value("found an unscanned lease"))
                .andExpect(jsonPath("$.sourceUuid").doesNotExist());
    }

    @Test
    void createChargeTerm_shouldReturn404_whenTheTenancyDoesNotExist() throws Exception {
        // Act / Assert
        mockMvc.perform(post("/api/tenancy-charge-terms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "tenancy" : "%s",
                            "agreementType" : "LAND",
                            "validAt" : "2026-09-01",
                            "source" : "MIGRATION"
                        }
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    // Both gates answer 409, so the message is what tells them apart -- which is
    // the reason this controller's handler carries a body.
    @Test
    void createChargeTerm_shouldReturn409_whenTheLotDoesNotPermitTheAgreementType() throws Exception {
        // Arrange -- the park offers STORAGE, this space does not
        copyTemplateToProperty("IT CT Storage Terms", "STORAGE");

        // Act / Assert
        createTerm("STORAGE", "2026-09-01", "MIGRATION")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("does not permit")));
    }

    @Test
    void createChargeTerm_shouldReturn409_whenThePropertyHasNoTemplateForThatType() throws Exception {
        // Arrange -- this space could host STORAGE, but the park never took it on
        permitAgreementType(lot, AgreementType.STORAGE, null);

        // Act / Assert
        createTerm("STORAGE", "2026-09-01", "MIGRATION")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("terms template")));
    }

    @Test
    void createChargeTerm_shouldPreferTheLotRate_overTheTemplateRate() throws Exception {
        // Arrange -- rates are set while looking at lots on the map
        setLotRate(lot, AgreementType.LAND, new BigDecimal("725.00"));

        // Act
        MvcResult result = createTerm("LAND", "2026-09-01", "MIGRATION")
                .andExpect(status().isCreated())
                .andReturn();

        // Assert
        assertEquals(0, new BigDecimal("725.00").compareTo(fetch(result).rate()));
    }

    // ---- patch ---------------------------------------------------------------

    @Test
    void patchChargeTerm_shouldMapCamelCaseOntoColumns() throws Exception {
        // Arrange
        UUID uuid = createdTermId();

        // Act
        patchTerm(uuid, """
                {
                    "rate" : 812.50,
                    "gracePeriodDays" : 3,
                    "note" : "negotiated"
                }
                """)
                // Assert
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(812.50))
                .andExpect(jsonPath("$.gracePeriodDays").value(3))
                .andExpect(jsonPath("$.note").value("negotiated"));
    }

    // agreement_type is absent from the controller's map AND from the
    // repository's whitelist. The request is accepted; the field is not.
    @Test
    void patchChargeTerm_shouldIgnoreAgreementType() throws Exception {
        // Arrange
        UUID uuid = createdTermId();

        // Act
        patchTerm(uuid, """
                {
                    "agreementType" : "STORAGE",
                    "note" : "trying it on"
                }
                """)
                // Assert
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agreementType").value("LAND"))
                .andExpect(jsonPath("$.note").value("trying it on"));
    }

    @Test
    void patchChargeTerm_shouldReturn400_forSubmeteredTrash() throws Exception {
        // Arrange -- trash is collected per container; there is nothing to meter
        UUID uuid = createdTermId();

        // Act / Assert
        patchTerm(uuid, """
                { "trashMethod" : "SUBMETERED" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("trash_method")));
    }

    @Test
    void patchChargeTerm_shouldAcceptAFlatMethodBeforeItsAmount() throws Exception {
        // Arrange -- a draft is filled in one field at a time
        UUID uuid = createdTermId();

        // Act / Assert
        patchTerm(uuid, """
                { "waterMethod" : "FLAT" }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waterMethod").value("FLAT"));
    }

    @Test
    void patchChargeTerm_shouldReturn400_onceTheTermHasLeftProposed() throws Exception {
        // Arrange
        UUID uuid = createdTermId();
        setLotRate(lot, AgreementType.LAND, new BigDecimal("650.00"));
        patchTerm(uuid, """
                { "rate" : 650.00 }
                """).andExpect(status().isOk());
        submit(uuid).andExpect(status().isOk());

        // Act / Assert
        patchTerm(uuid, """
                { "note" : "too late" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("PENDING")));
    }

    // ---- submit --------------------------------------------------------------

    // The payoff for carrying a message body: seven method/amount pairs plus the
    // cars rule plus the rate can all be wrong at once, and an empty 400 would
    // leave the office worker guessing which one.
    @Test
    void submit_shouldReturn400_namingEveryFieldThatIsNotReady() throws Exception {
        // Arrange -- no rate anywhere, and cars_max below allowed_cars
        UUID uuid = createdTermId();
        patchTerm(uuid, """
                {
                    "allowedCars" : 6,
                    "carsMax" : 2
                }
                """).andExpect(status().isOk());

        // Act / Assert
        submit(uuid)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("rate")))
                .andExpect(jsonPath("$.message").value(containsString("cars_max")));
    }

    @Test
    void submit_shouldReturn200_andMoveAReadyTermToPending() throws Exception {
        // Arrange
        UUID uuid = createdTermId();
        patchTerm(uuid, """
                { "rate" : 650.00 }
                """).andExpect(status().isOk());

        // Act / Assert
        submit(uuid)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.editable").value(false));
    }

    @Test
    void activate_shouldPutAMigratedTermInForce_withNoDocument() throws Exception {
        // Arrange -- term_in_force_needs_paper allows MIGRATION and CORRECTION
        UUID uuid = createdTermId();
        patchTerm(uuid, """
                { "rate" : 650.00 }
                """).andExpect(status().isOk());
        submit(uuid).andExpect(status().isOk());

        // Act / Assert
        mockMvc.perform(post("/api/tenancy-charge-terms/{uuid}/activate", uuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ---- cancel and delete ---------------------------------------------------

    @Test
    void cancel_shouldReturn400_withoutAReason() throws Exception {
        // Arrange -- term_cancel_facts needs all three cancel columns
        UUID uuid = createdTermId();

        // Act / Assert
        mockMvc.perform(post("/api/tenancy-charge-terms/{uuid}/cancel", uuid)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        { "cancelReason" : "  " }
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteChargeTerm_shouldReturn204_forADraft() throws Exception {
        // Arrange
        UUID uuid = createdTermId();

        // Act / Assert
        mockMvc.perform(delete("/api/tenancy-charge-terms/{uuid}", uuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/tenancy-charge-terms/{uuid}", uuid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ---- reads ---------------------------------------------------------------

    @Test
    void listByTenancy_shouldReturnTheTermsForThatTenancy() throws Exception {
        // Arrange
        createTerm("LAND", "2026-09-01", "MIGRATION").andExpect(status().isCreated());
        createTerm("LAND", "2026-10-01", "CORRECTION", "rent was wrong in the migration")
                .andExpect(status().isCreated());

        // Act / Assert -- newest first
        mockMvc.perform(get("/api/tenancy-charge-terms")
                        .param("tenancy", tenancy.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].validAt").value("2026-10-01"));
    }

    @Test
    void getInForceOn_shouldReturn404_whenNothingIsInForce() throws Exception {
        // Arrange -- a PROPOSED term is not in force
        createdTermId();

        // Act / Assert
        mockMvc.perform(get("/api/tenancy-charge-terms/in-force")
                        .param("tenancy", tenancy.toString())
                        .param("on", "2026-09-01")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ---- the response DTO ----------------------------------------------------

    // Returning the domain record would put isSoftDeleted(), isDeletable(),
    // isCancelled() and isInForce() on the wire as bare booleans. The DTO exposes
    // one of them, deliberately, because the form needs it.
    @Test
    void response_shouldExposeOnlyTheChosenFields() throws Exception {
        // Act / Assert
        createTerm("LAND", "2026-09-01", "MIGRATION")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.softDeleted").doesNotExist())
                .andExpect(jsonPath("$.deletedAt").doesNotExist())
                .andExpect(jsonPath("$.deletable").doesNotExist())
                .andExpect(jsonPath("$.cancelled").doesNotExist())
                .andExpect(jsonPath("$.inForce").doesNotExist())
                .andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    void endpoints_shouldReturn401_withoutAToken() throws Exception {
        // Act / Assert
        mockMvc.perform(get("/api/tenancy-charge-terms").param("tenancy", tenancy.toString()))
                .andExpect(status().isUnauthorized());
    }

    // ---- Helpers -------------------------------------------------------------

    private ResultActions createTerm(String agreementType, String validAt, String source) throws Exception {
        return createTerm(agreementType, validAt, source, null);
    }

    private ResultActions createTerm(String agreementType, String validAt, String source,
                                     String correctionReason) throws Exception {
        String reason = correctionReason == null ? "null" : "\"" + correctionReason + "\"";
        return mockMvc.perform(post("/api/tenancy-charge-terms")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "tenancy" : "%s",
                            "agreementType" : "%s",
                            "validAt" : "%s",
                            "source" : "%s",
                            "correctionReason" : %s
                        }
                        """.formatted(tenancy, agreementType, validAt, source, reason)));
    }

    private UUID createdTermId() throws Exception {
        MvcResult result = createTerm("LAND", "2026-09-01", "MIGRATION")
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.uuid"));
    }

    private ResultActions patchTerm(UUID uuid, String body) throws Exception {
        return mockMvc.perform(patch("/api/tenancy-charge-terms/{uuid}", uuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions submit(UUID uuid) throws Exception {
        return mockMvc.perform(post("/api/tenancy-charge-terms/{uuid}/submit", uuid)
                .header("Authorization", "Bearer " + token));
    }

    private void copyTemplateToProperty(String name, String agreementType) throws Exception {
        MvcResult global = mockMvc.perform(post("/api/terms-templates/global")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "name" : "%s",
                            "agreementType" : "%s"
                        }
                        """.formatted(name, agreementType)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID templateId = UUID.fromString(
                JsonPath.read(global.getResponse().getContentAsString(), "$.uuid"));

        mockMvc.perform(post("/api/terms-templates/{uuid}/copy", templateId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        { "propertyId" : "%s" }
                        """.formatted(property)))
                .andExpect(status().isCreated());
    }

    /**
     * Written straight to the table: LotService.createLot does not add a
     * permissible-use row, and LotService wraps neither save nor delete on
     * LotPermissibleAgreementTypeRepository, so there is no public way to say
     * "this lot may host a LAND agreement" yet.
     */
    private void permitAgreementType(UUID lotId, AgreementType agreementType, BigDecimal targetRate) {
        jdbc.sql("""
                INSERT INTO lot_permissible_agreement_type (lot_id, agreement_type, target_rate, asking_rate)
                VALUES (:lotId, :agreementType::agreement_type, :targetRate, :targetRate)
                """)
                .param("lotId", lotId)
                .param("agreementType", agreementType.name())
                .param("targetRate", targetRate)
                .update();
    }

    private void setLotRate(UUID lotId, AgreementType agreementType, BigDecimal targetRate) {
        jdbc.sql("""
                UPDATE lot_permissible_agreement_type
                SET target_rate = :targetRate, asking_rate = :targetRate
                WHERE lot_id = :lotId AND agreement_type = :agreementType::agreement_type
                """)
                .param("lotId", lotId)
                .param("agreementType", agreementType.name())
                .param("targetRate", targetRate)
                .update();
    }

    /** Read the response's uuid, then load the real record -- the DTO hides fields on purpose. */
    private TenancyChargeTerm fetch(MvcResult result) throws Exception {
        UUID uuid = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.uuid"));
        return tenancyChargeTermService.findById(uuid).orElseThrow();
    }
}
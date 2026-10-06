package io.github.lordship.termstemplate.internal;

import com.jayway.jsonpath.JsonPath;
import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.properties.internal.PropertyRow;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.FeeMethod;
import io.github.lordship.shared.UtilityMethod;
import io.github.lordship.termstemplate.TermsTemplate;
import io.github.lordship.termstemplate.TermsTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
public class TermsTemplateControllerIT extends IntegrationTest {

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TermsTemplateService termsTemplateService;

    // ── Global templates ─────────────────────────────────────────────────────

    @Test
    void listGlobalTemplates_shouldReturnTheSeededSets_withAgreementTypeMapped() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        // Act
        mockMvc.perform(get("/api/terms-templates/global")
                        .header("Authorization", "Bearer " + token))
                // Assert -- V1 seeds three, and agreementType coming back non-null is the
                // end-to-end proof that the PG enum maps into Java.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$[0].agreementType").exists())
                .andExpect(jsonPath("$[0].propertyId").doesNotExist());
    }

    @Test
    void createGlobalTemplate_shouldReturn201_andApplyDatabaseDefaults() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        // Act
        MvcResult result = mockMvc.perform(post("/api/terms-templates/global")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "name" : "IT Land Terms",
                            "agreementType" : "LAND"
                        }
                        """))
                // Assert
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("IT Land Terms"))
                .andReturn();

        // The minimal insert names three columns; everything else must come from
        // the DB defaults, not from Java nulls.
        TermsTemplate created = fetch(result);
        assertNull(created.property(), "a global template has no property");
        assertEquals(2, created.allowedCars());
        assertEquals(4, created.carsMax());
        assertEquals(1, created.paymentDueDay());
        assertEquals(7, created.gracePeriodDays());
        assertEquals(FeeMethod.FLAT, created.lateFeeMethod());
        assertEquals(UtilityMethod.NONE, created.waterMethod());
        assertNull(created.targetRate(),
                "a global rent default is meaningless across markets");
    }

    @Test
    void createGlobalTemplate_shouldReturn409_whenNameIsAlreadyTaken() throws Exception {
        // Arrange -- V1 seeds this one
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        // Act
        mockMvc.perform(post("/api/terms-templates/global")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "name" : "Standard Manufactured Home Lot Terms",
                            "agreementType" : "LAND"
                        }
                        """))
                // Assert -- the name is a global template's identity
                .andExpect(status().isConflict());
    }

    // ── Copying into a property ──────────────────────────────────────────────

    @Test
    void copyTemplateToProperty_shouldReturn201_andCarryEveryValue_andRecordProvenance() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        PropertyRow property = testData.insertProperty("CP");
        UUID templateId = createTemplate(token, "IT Copy Source", "LAND");

        // The template is edited before copying, so the copy has something to carry.
        patchTemplate(token, templateId, """
                {
                    "carsMax" : 6,
                    "carFee" : 45.00,
                    "lateFeeMethod" : "PERCENT_OF_RENT",
                    "lateFeeAmount" : 1.5
                }
                """).andExpect(status().isOk());

        // Act
        MvcResult result = mockMvc.perform(post("/api/terms-templates/{uuid}/copy", templateId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                        { "propertyId" : "%s" }
                        """, property.uuid())))
                // Assert
                .andExpect(status().isCreated())
                .andReturn();

        TermsTemplate copy = fetch(result);
        assertEquals(property.uuid(), copy.property());
        assertNotEquals(templateId, copy.uuid(), "the copy gets its own identity");
        assertEquals(templateId, copy.copiedFrom(), "provenance link back to the template");
        assertEquals(6, copy.carsMax(), "carsMax must survive the copy");
        assertEquals(FeeMethod.PERCENT_OF_RENT, copy.lateFeeMethod());
        assertEquals(0, new BigDecimal("1.5").compareTo(copy.lateFeeAmount()));
    }

    @Test
    void copyTemplateToProperty_shouldReturn409_whenThePropertyAlreadyHasThatAgreementType() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        PropertyRow property = testData.insertProperty("DP");
        UUID first = createTemplate(token, "IT Dup A", "LAND");
        UUID second = createTemplate(token, "IT Dup B", "LAND");

        copyToProperty(token, first, property.uuid()).andExpect(status().isCreated());

        // Act
        copyToProperty(token, second, property.uuid())
                // Assert -- a property may hold at most one set per agreement type
                .andExpect(status().isConflict());
    }

    @Test
    void copyTemplateToProperty_shouldReturn400_whenTheSourceIsAlreadyPropertyLevel() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        PropertyRow source = testData.insertProperty("SP");
        PropertyRow target = testData.insertProperty("TG");
        UUID templateId = createTemplate(token, "IT Chain Source", "LAND");

        MvcResult copied = copyToProperty(token, templateId, source.uuid())
                .andExpect(status().isCreated())
                .andReturn();
        UUID propertyLevelId = UUID.fromString(JsonPath.read(copied.getResponse().getContentAsString(), "$.id"));

        // Act -- only a global template may be copied in
        copyToProperty(token, propertyLevelId, target.uuid())
                // Assert
                .andExpect(status().isBadRequest());
    }

    @Test
    void copyTemplateToProperty_shouldReturn404_whenTheTemplateDoesNotExist() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        PropertyRow property = testData.insertProperty("NF");

        // Act
        copyToProperty(token, UUID.randomUUID(), property.uuid())
                // Assert
                .andExpect(status().isNotFound());
    }

    @Test
    void editingAPropertyCopy_shouldNotChangeTheTemplateItCameFrom() throws Exception {
        // Arrange -- this is the frozen-on-copy rule, end to end
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        PropertyRow property = testData.insertProperty("FZ");
        UUID templateId = createTemplate(token, "IT Frozen Source", "LAND");

        MvcResult copied = copyToProperty(token, templateId, property.uuid())
                .andExpect(status().isCreated())
                .andReturn();
        UUID copyId = UUID.fromString(JsonPath.read(copied.getResponse().getContentAsString(), "$.id"));

        // Act -- set a real rent on the property's copy
        patchTemplate(token, copyId, """
                { "targetRate" : 700.00 }
                """).andExpect(status().isOk());

        // Assert
        TermsTemplate copy = termsTemplateService.findById(copyId).orElseThrow();
        TermsTemplate template = termsTemplateService.findById(templateId).orElseThrow();

        assertEquals(0, new BigDecimal("700.00").compareTo(copy.targetRate()));
        assertNull(template.targetRate(),
                "a global template must not move when a property edits its copy");
    }

    @Test
    void listByProperty_shouldReturnOnlyThatPropertysSets() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        PropertyRow mine = testData.insertProperty("M1");
        PropertyRow theirs = testData.insertProperty("T1");
        copyToProperty(token, createTemplate(token, "IT Scope A", "LAND"), mine.uuid())
                .andExpect(status().isCreated());
        copyToProperty(token, createTemplate(token, "IT Scope B", "LAND"), theirs.uuid())
                .andExpect(status().isCreated());

        // Act
        mockMvc.perform(get("/api/terms-templates")
                        .param("propertyId", mine.uuid().toString())
                        .header("Authorization", "Bearer " + token))
                // Assert
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("IT Scope A"));
    }

    // ── Fee vocabularies, through HTTP and the CHECK constraints ─────────────

    @Test
    void patchTemplate_shouldAcceptPercentOfRentLateFee() throws Exception {
        // Arrange -- the only place the V1 CHECK constraint is actually exercised
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID templateId = createTemplate(token, "IT Percent Late Fee", "LAND");

        // Act -- "one and a half percent of the monthly lot rent"
        patchTemplate(token, templateId, """
                {
                    "lateFeeMethod" : "PERCENT_OF_RENT",
                    "lateFeeAmount" : 1.5
                }
                """)
                // Assert
                .andExpect(status().isOk());

        TermsTemplate updated = termsTemplateService.findById(templateId).orElseThrow();
        assertEquals(FeeMethod.PERCENT_OF_RENT, updated.lateFeeMethod());
        assertEquals(0, new BigDecimal("1.5").compareTo(updated.lateFeeAmount()),
                "the percentage must survive; it is not a flat amount to be zeroed");
    }

    @Test
    void patchTemplate_shouldAcceptBankOrFlatNsfFee() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID templateId = createTemplate(token, "IT Bank Or Flat NSF", "LAND");

        // Act -- "$35.00, or as charged by the financial institution, whichever is greater"
        patchTemplate(token, templateId, """
                {
                    "nsfFeeMethod" : "BANK_OR_FLAT",
                    "nsfFeeAmount" : 35.00
                }
                """)
                // Assert
                .andExpect(status().isOk());

        TermsTemplate updated = termsTemplateService.findById(templateId).orElseThrow();
        assertEquals(FeeMethod.BANK_OR_FLAT, updated.nsfFeeMethod());
        assertEquals(0, new BigDecimal("35.00").compareTo(updated.nsfFeeAmount()));
    }

    @Test
    void patchTemplate_shouldReturn400_whenBankOrFlatIsUsedForALateFee() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID templateId = createTemplate(token, "IT Wrong Late Method", "LAND");

        // Act -- a late fee cannot be whatever the bank charged
        patchTemplate(token, templateId, """
                {
                    "lateFeeMethod" : "BANK_OR_FLAT",
                    "lateFeeAmount" : 35.00
                }
                """)
                // Assert -- rejected in Java, with a readable message, not by the column CHECK
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchTemplate_shouldZeroTheAmount_whenAFeeMethodBecomesNone() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID templateId = createTemplate(token, "IT No Late Fee", "LAND");

        // Act
        patchTemplate(token, templateId, """
                { "lateFeeMethod" : "NONE" }
                """)
                // Assert
                .andExpect(status().isOk());

        TermsTemplate updated = termsTemplateService.findById(templateId).orElseThrow();
        assertEquals(FeeMethod.NONE, updated.lateFeeMethod());
        assertEquals(0, BigDecimal.ZERO.compareTo(updated.lateFeeAmount()),
                "the CHECK constraint requires zero when the method is NONE");
    }

    @Test
    void patchTemplate_shouldIgnoreAgreementType_becauseIdentityIsNotPatchable() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID templateId = createTemplate(token, "IT Identity Guard", "LAND");

        // Act
        patchTemplate(token, templateId, """
                {
                    "agreementType" : "STORAGE",
                    "name" : "IT Identity Guard Renamed"
                }
                """)
                // Assert
                .andExpect(status().isOk());

        TermsTemplate updated = termsTemplateService.findById(templateId).orElseThrow();
        assertEquals(AgreementType.LAND, updated.agreementType(), "agreement type is not patchable");
        assertEquals("IT Identity Guard Renamed", updated.name(), "name is");
    }

    // ── Delete and auth ──────────────────────────────────────────────────────

    @Test
    void deleteTemplate_shouldReturn204_andSubsequentGetReturns404() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID templateId = createTemplate(token, "IT Doomed Terms", "LAND");

        // Act
        mockMvc.perform(delete("/api/terms-templates/{uuid}", templateId)
                        .header("Authorization", "Bearer " + token))
                // Assert
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/terms-templates/{uuid}", templateId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void getTemplate_shouldReturn404_whenItDoesNotExist() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        // Act / Assert
        mockMvc.perform(get("/api/terms-templates/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void listGlobalTemplates_shouldBeRejected_whenNoTokenProvided() throws Exception {
        mockMvc.perform(get("/api/terms-templates/global"))
                .andExpect(status().isUnauthorized());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private UUID createTemplate(String token, String name, String agreementType) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/terms-templates/global")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                        {
                            "name" : "%s",
                            "agreementType" : "%s"
                        }
                        """, name, agreementType)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private org.springframework.test.web.servlet.ResultActions copyToProperty(
            String token, UUID templateId, UUID propertyId) throws Exception {
        return mockMvc.perform(post("/api/terms-templates/{uuid}/copy", templateId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("""
                        { "propertyId" : "%s" }
                        """, propertyId)));
    }

    private org.springframework.test.web.servlet.ResultActions patchTemplate(
            String token, UUID uuid, String body) throws Exception {
        return mockMvc.perform(patch("/api/terms-templates/{uuid}", uuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** Read the response's uuid, then load the real row -- the DTO may not expose every field. */
    private TermsTemplate fetch(MvcResult result) throws Exception {
        UUID uuid = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
        return termsTemplateService.findById(uuid).orElseThrow();
    }
}
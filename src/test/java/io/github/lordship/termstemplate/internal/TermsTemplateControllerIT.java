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
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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

    // ── A new template that starts from another ──────────────────────────────

    @Test
    void createGlobalTemplate_shouldStartFromATemplatesTermsAndRates_whenCopyFromIdIsGiven() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID sourceId = createTemplate(token, "IT Start Source", "LAND");
        patchTemplate(token, sourceId, """
                {
                    "targetRate" : 640.00,
                    "gracePeriodDays" : 9,
                    "carsMax" : 6
                }
                """).andExpect(status().isOk());

        // Act
        MvcResult result = createTemplateFrom(token, "IT Started From Template", "LAND", sourceId)
                // Assert
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("IT Started From Template"))
                .andExpect(jsonPath("$.propertyId").doesNotExist())
                .andReturn();

        TermsTemplate created = fetch(result);
        assertNotEquals(sourceId, created.uuid(), "a new template, not the source again");
        assertNull(created.property(), "a global template has no property");
        assertNull(created.copiedFrom(), "copiedFrom stays empty on a global template");
        assertEquals(0, new BigDecimal("640.00").compareTo(created.targetRate()));
        assertEquals(9, created.gracePeriodDays());
        assertEquals(6, created.carsMax());
    }

    @Test
    void createGlobalTemplate_shouldLeaveTheRatesEmpty_whenStartedFromAPropertysDeal() throws Exception {
        // Arrange -- the deal of one property, with that property's rent on the deal
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID templateId = createTemplate(token, "IT Deal Source", "LAND");
        UUID dealId = dealAt(token, templateId, "CQ");
        patchTemplate(token, dealId, """
                {
                    "targetRate" : 640.00,
                    "gracePeriodDays" : 9
                }
                """).andExpect(status().isOk());

        // Act
        MvcResult result = createTemplateFrom(token, "IT Started From Deal", "LAND", dealId)
                // Assert
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.propertyId").doesNotExist())
                .andReturn();

        TermsTemplate created = fetch(result);
        assertNull(created.property(), "a global template has no property");
        assertNull(created.copiedFrom(), "copiedFrom stays empty on a global template");
        assertNull(created.targetRate(), "one property's rent is not a rent for every property");
        assertNull(created.askingRate());
        assertEquals(9, created.gracePeriodDays(), "every other term comes from the deal");
    }

    @Test
    void createGlobalTemplate_shouldReturn404_whenTheTemplateToStartFromDoesNotExist() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        // Act / Assert
        createTemplateFrom(token, "IT Started From Nothing", "LAND", UUID.randomUUID())
                .andExpect(status().isNotFound());
    }

    // ── Renaming a global template ───────────────────────────────────────────

    @Test
    void patchTemplate_shouldReturn409_whenAGlobalTemplateTakesAnotherTemplatesName() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        createTemplate(token, "IT Name Held", "LAND");
        UUID renamedId = createTemplate(token, "IT Name Wanted", "LAND");

        // Act -- the name is a global template's identity, in any letter case
        patchTemplate(token, renamedId, """
                { "name" : "it name held" }
                """)
                // Assert
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("terms_template.name_taken"));

        assertEquals("IT Name Wanted", termsTemplateService.findById(renamedId).orElseThrow().name());
    }

    // ── The same changes to several deals ────────────────────────────────────

    @Test
    void patchMany_shouldChangeEveryDealNamed_andLeaveTheTemplateAlone() throws Exception {
        // Arrange -- two properties that copied the same template
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID templateId = createTemplate(token, "IT Many Source", "LAND");
        UUID firstDeal = dealAt(token, templateId, "MA");
        UUID secondDeal = dealAt(token, templateId, "MB");

        // Act -- an amount as text, the way Glade sends money
        patchMany(token, """
                {
                    "ids" : ["%s", "%s"],
                    "changes" : {
                        "gracePeriodDays" : 5,
                        "lateFeeMethod" : "PERCENT_OF_RENT",
                        "lateFeeAmount" : "1.50"
                    }
                }
                """.formatted(firstDeal, secondDeal))
                // Assert -- the deals as they are afterwards, in the order asked
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(firstDeal.toString()))
                .andExpect(jsonPath("$[1].id").value(secondDeal.toString()))
                .andExpect(jsonPath("$[1].gracePeriodDays").value(5));

        for (UUID dealId : List.of(firstDeal, secondDeal)) {
            TermsTemplate deal = termsTemplateService.findById(dealId).orElseThrow();
            assertEquals(5, deal.gracePeriodDays());
            assertEquals(FeeMethod.PERCENT_OF_RENT, deal.lateFeeMethod());
            assertEquals(0, new BigDecimal("1.50").compareTo(deal.lateFeeAmount()));
        }

        TermsTemplate template = termsTemplateService.findById(templateId).orElseThrow();
        assertEquals(7, template.gracePeriodDays(), "the template the deals were copied from keeps its terms");
        assertEquals(FeeMethod.FLAT, template.lateFeeMethod());
    }

    @Test
    void patchMany_shouldReturn409_whenOneOfTheTemplatesDoesNotExist() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID templateId = createTemplate(token, "IT Many Gone", "LAND");

        // Act / Assert -- that nothing is kept from such a call is TermsTemplateManyIT's to show:
        // inside this class's transaction the undo cannot be seen
        patchMany(token, """
                {
                    "ids" : ["%s", "%s"],
                    "changes" : { "gracePeriodDays" : 5 }
                }
                """.formatted(templateId, UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("terms_template.gone"));
    }

    @Test
    void patchMany_shouldReturn400_whenNoTemplateIsNamed() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        // Act / Assert
        patchMany(token, """
                {
                    "ids" : [],
                    "changes" : { "gracePeriodDays" : 5 }
                }
                """)
                .andExpect(status().isBadRequest());
    }

    // ── The Templates and deals screen ───────────────────────────────────────

    @Test
    void templatesScreen_shouldListTemplates_dealsWithTheirTemplate_andDeletedTemplates() throws Exception {
        // Arrange
        String token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        UUID templateId = createTemplate(token, "IT Screen Source", "LAND");
        UUID dealId = dealAt(token, templateId, "MS");
        UUID deletedId = createTemplate(token, "IT Screen Deleted", "LAND");
        mockMvc.perform(delete("/api/terms-templates/{uuid}", deletedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // Act
        mockMvc.perform(get("/api/screens/rates/templates")
                        .header("Authorization", "Bearer " + token))
                // Assert
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates[*].id", hasItem(templateId.toString())))
                .andExpect(jsonPath("$.templates[*].id", not(hasItem(deletedId.toString()))))
                .andExpect(jsonPath("$.templates[*].id", not(hasItem(dealId.toString()))))
                .andExpect(jsonPath("$.deals[*].deal.id", hasItem(dealId.toString())))
                .andExpect(jsonPath("$.deals[?(@.deal.id == '%s')].copiedFrom".formatted(dealId),
                        hasItem(templateId.toString())))
                .andExpect(jsonPath("$.deleted[*].id", hasItem(deletedId.toString())));
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

    // A new global template that starts from another template, or from a property's deal
    private org.springframework.test.web.servlet.ResultActions createTemplateFrom(
            String token, String name, String agreementType, UUID copyFromId) throws Exception {
        return mockMvc.perform(post("/api/terms-templates/global")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("""
                        {
                            "name" : "%s",
                            "agreementType" : "%s",
                            "copyFromId" : "%s"
                        }
                        """, name, agreementType, copyFromId)));
    }

    // The same changes to several templates at once
    private org.springframework.test.web.servlet.ResultActions patchMany(String token, String body) throws Exception {
        return mockMvc.perform(patch("/api/terms-templates")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // A new property with the given code that copies the template: the id of the property's deal
    private UUID dealAt(String token, UUID templateId, String propertyCode) throws Exception {
        PropertyRow property = testData.insertProperty(propertyCode);
        MvcResult copied = copyToProperty(token, templateId, property.uuid())
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(copied.getResponse().getContentAsString(), "$.id"));
    }

    /** Read the response's uuid, then load the real row -- the DTO may not expose every field. */
    private TermsTemplate fetch(MvcResult result) throws Exception {
        UUID uuid = UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
        return termsTemplateService.findById(uuid).orElseThrow();
    }
}
package io.github.lordship.instruments.internal;

import com.jayway.jsonpath.JsonPath;
import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.lots.internal.LotRow;
import io.github.lordship.properties.internal.PropertyRow;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.tenancy.internal.TenancyRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The lease as it would come out, before anybody commits to it.
 *
 * <p>Preview runs the same assembly generate does, so these are really tests of
 * generate: what it will produce, and what it will refuse.
 *
 * <p>An IT rather than a service test because every step of the fixture writes
 * an audit row, and an audit row needs somebody to attribute it to.
 */
@Transactional
public class InstrumentPreviewIT extends IntegrationTest {

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
    private UUID lot;
    private UUID tenancy;
    private UUID template;
    private UUID section;

    @BeforeEach
    void setUp() throws Exception {
        token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        PropertyRow propertyRow = testData.insertProperty("PV");
        LotRow lotRow = testData.insertLot(propertyRow.uuid(), "7");
        TenancyRow tenancyRow = testData.insertTenancy(lotRow.uuid());
        property = propertyRow.uuid();
        lot = lotRow.uuid();
        tenancy = tenancyRow.uuid();

        permitAgreementType(lot, AgreementType.LAND, new BigDecimal("650.00"));
        copyTermsTemplateToProperty("PV Land Terms", "LAND");

        template = createDocumentTemplate("PV Land Lease");
        section = createSection(template, "Rent");
    }

    // ---- what it produces ----------------------------------------------------

    @Test
    void preview_shouldSubstituteThisTenantsFiguresIntoTheClause() throws Exception {
        // Arrange
        clause("rent", "The monthly rent is {{term.rate}}.");
        assignDocument();
        UUID instrument = draftWithTerm();

        // Act + Assert -- the real figure, not the token
        mockMvc.perform(get("/api/instruments/{uuid}/preview", instrument)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].clauses[0].body")
                        .value("The monthly rent is $650.00."))
                .andExpect(jsonPath("$.sections[0].clauses[0].origin").value("TEMPLATE"));
    }

    @Test
    void preview_shouldSayWhichDocumentItCameFrom() throws Exception {
        // Arrange -- the version is what a render will freeze onto the instrument
        clause("rent", "The monthly rent is {{term.rate}}.");
        assignDocument();

        // Act + Assert
        mockMvc.perform(get("/api/instruments/{uuid}/preview", draftWithTerm())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentTemplateId").value(template.toString()))
                .andExpect(jsonPath("$.documentName").value("PV Land Lease"))
                .andExpect(jsonPath("$.documentVersion").exists());
    }

    @Test
    void preview_shouldNumberATypedClauseInWithTheTemplates() throws Exception {
        // Arrange -- the shed the seller left behind
        clause("rent", "The monthly rent is {{term.rate}}.");
        assignDocument();
        UUID instrument = draftWithTerm();
        typeClauseOnto(instrument, "Tenant may keep the existing shed until June 1.");

        // Act + Assert -- one numbered list, not the template's plus an afterthought
        mockMvc.perform(get("/api/instruments/{uuid}/preview", instrument)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].clauses.length()").value(2))
                .andExpect(jsonPath("$.sections[0].clauses[0].ordinal").value(1))
                .andExpect(jsonPath("$.sections[0].clauses[1].ordinal").value(2))
                .andExpect(jsonPath("$.sections[0].clauses[1].origin").value("INSTRUMENT"))
                .andExpect(jsonPath("$.sections[0].clauses[1].body")
                        .value("Tenant may keep the existing shed until June 1."));
    }

    @Test
    void preview_shouldBeComplete_whenNothingIsMissing() throws Exception {
        // Arrange
        clause("rent", "The monthly rent is {{term.rate}}.");
        assignDocument();

        // Act + Assert -- the one field the Generate button reads
        mockMvc.perform(get("/api/instruments/{uuid}/preview", draftWithTerm())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.unresolved").isEmpty())
                .andExpect(jsonPath("$.omittedRequired").isEmpty());
    }

    // ---- what it refuses to hide ---------------------------------------------

    @Test
    void preview_shouldShowTheHole_ratherThanFailing() throws Exception {
        // Arrange -- this park was set up without an entity named on it, so
        // landlord.name has nothing behind it
        clause("rent", "Rent is {{term.rate}}, payable to {{landlord.name}}.");
        assignDocument();

        // Act + Assert -- 200 with the hole standing in the text. She fixes the
        // property and looks again; she does not read a stack trace
        mockMvc.perform(get("/api/instruments/{uuid}/preview", draftWithTerm())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.unresolved[*].token", hasItem("landlord.name")))
                .andExpect(jsonPath("$.unresolved[?(@.token == 'landlord.name')].source",
                        hasItem("PROPERTY")))
                .andExpect(jsonPath("$.unresolved[*].token", not(hasItem("term.rate"))))
                .andExpect(jsonPath("$.sections[0].clauses[0].body")
                        .value(containsString("{{landlord.name}}")))
                .andExpect(jsonPath("$.sections[0].clauses[0].body")
                        .value(containsString("$650.00")))
                // and the clause itself says what it could not fill, so the
                // screen can highlight it where it sits rather than in a footnote
                .andExpect(jsonPath("$.sections[0].clauses[0].unresolved",
                        hasItem("landlord.name")));
    }

    @Test
    void preview_shouldReportARequiredSectionThatEndedUpEmpty() throws Exception {
        // Arrange -- a mandated disclosure whose only clause is for another deal
        UUID disclosure = createSection(template, "Rent History Disclosure");
        requireSection(disclosure, "RCW 59.20.045");
        conditionalClause(disclosure, "history", "Rents were as follows.",
                "term.water_method", "SUBMETERED");
        clause("rent", "The monthly rent is {{term.rate}}.");
        assignDocument();

        // Act + Assert -- a missing mandated disclosure is a legal problem, not
        // a formatting one, so it is named rather than silently dropped
        mockMvc.perform(get("/api/instruments/{uuid}/preview", draftWithTerm())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.omittedRequired",
                        hasItem("Rent History Disclosure (RCW 59.20.045)")));
    }


    @Test
    void preview_shouldPinAHoleToTheClauseThatWantedIt() throws Exception {
        // Arrange -- one clause complete, the next one short a value
        clause("rent", "The monthly rent is {{term.rate}}.");
        secondClause("notices", "Notices go to {{landlord.name}}.");
        assignDocument();

        // Act + Assert -- she is told which paragraph to look at, not just that
        // something somewhere is missing
        mockMvc.perform(get("/api/instruments/{uuid}/preview", draftWithTerm())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.sections[0].clauses[0].unresolved").isEmpty())
                .andExpect(jsonPath("$.sections[0].clauses[1].clauseKey").value("notices"))
                .andExpect(jsonPath("$.sections[0].clauses[1].unresolved",
                        hasItem("landlord.name")))
                // said once at the top, whatever how many clauses wanted it
                .andExpect(jsonPath("$.unresolved.length()").value(1))
                .andExpect(jsonPath("$.unresolved[0].token").value("landlord.name"))
                .andExpect(jsonPath("$.unresolved[0].message")
                        .value("This is set on the property"));
    }

    // ---- reasons there is no paper to show -----------------------------------

    @Test
    void preview_shouldReturn409_whenNoDealIsAttached() throws Exception {
        // Arrange -- a lease draft whose rent schedule was never confirmed
        clause("rent", "The monthly rent is {{term.rate}}.");
        assignDocument();
        UUID instrument = createDraft();

        // Act + Assert
        mockMvc.perform(get("/api/instruments/{uuid}/preview", instrument)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("instrument.no_term"));
    }

    @Test
    void preview_shouldReturn409_whenThisParkHasNoDocumentForThisDeal() throws Exception {
        // Arrange -- the template exists but was never assigned to this park
        clause("rent", "The monthly rent is {{term.rate}}.");

        // Act + Assert
        mockMvc.perform(get("/api/instruments/{uuid}/preview", draftWithTerm())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("property.no_document"));
    }

    @Test
    void preview_shouldReturn404_forAnInstrumentThatDoesNotExist() throws Exception {
        // Act + Assert
        mockMvc.perform(get("/api/instruments/{uuid}/preview", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ---- fixtures ------------------------------------------------------------

    private UUID createDocumentTemplate(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/document-templates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "agreementType": "LAND",
                                  "instrumentType": "LEASE"
                                }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return uuidAt(result, "$.uuid");
    }

    private UUID createSection(UUID templateId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/document-templates/{uuid}/sections", templateId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "%s" }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        int index = JsonPath.<java.util.List<Object>>read(body, "$.sections").size() - 1;
        return UUID.fromString(JsonPath.read(body, "$.sections[" + index + "].uuid"));
    }

    private void clause(String key, String body) throws Exception {
        patchClause(addClause(section), """
                { "clauseKey": "%s", "body": "%s", "ordinal": 10 }
                """.formatted(key, body));
    }

    private void secondClause(String key, String body) throws Exception {
        patchClause(addClause(section), """
                { "clauseKey": "%s", "body": "%s", "ordinal": 20 }
                """.formatted(key, body));
    }

    private void conditionalClause(UUID sectionId, String key, String body,
                                   String field, String value) throws Exception {
        patchClause(addClause(sectionId), """
                {
                  "clauseKey": "%s",
                  "body": "%s",
                  "ordinal": 10,
                  "conditionField": "%s",
                  "conditionValues": ["%s"]
                }
                """.formatted(key, body, field, value));
    }

    private UUID addClause(UUID sectionId) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/document-templates/sections/{uuid}/clauses", sectionId)
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        java.util.List<String> ids = JsonPath.read(body,
                "$.sections[?(@.uuid == '" + sectionId + "')].clauses[*].uuid");
        return UUID.fromString(ids.get(ids.size() - 1));
    }

    private void patchClause(UUID clauseId, String json) throws Exception {
        mockMvc.perform(patch("/api/document-templates/clauses/{uuid}", clauseId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }

    private void requireSection(UUID sectionId, String statuteRef) throws Exception {
        mockMvc.perform(patch("/api/document-templates/sections/{uuid}", sectionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "required": true, "statuteRef": "%s" }
                                """.formatted(statuteRef)))
                .andExpect(status().isOk());
    }

    private void assignDocument() throws Exception {
        mockMvc.perform(post("/api/property-documents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "propertyId": "%s", "documentTemplateId": "%s" }
                                """.formatted(property, template)))
                .andExpect(status().isCreated());
    }

    /** A lease draft with its dates set and a one-step rent schedule confirmed. */
    private UUID draftWithTerm() throws Exception {
        UUID instrument = createDraft();
        mockMvc.perform(patch("/api/instruments/{uuid}", instrument)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "termStart": "2026-11-01",
                                  "termMonths": 60,
                                  "onExpiry": "MONTH_TO_MONTH"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/instruments/{uuid}/charge-terms/schedule", instrument)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "steps": [ { "validAt": "2026-11-01", "rate": 650.00 } ] }
                                """))
                .andExpect(status().isCreated());
        return instrument;
    }

    private UUID createDraft() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/instruments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "tenancyId": "%s", "type": "LEASE", "agreementType": "LAND" }
                                """.formatted(tenancy)))
                .andExpect(status().isCreated())
                .andReturn();
        return uuidAt(result, "$.uuid");
    }

    private void typeClauseOnto(UUID instrument, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/instruments/{uuid}/clauses", instrument)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "sectionId": "%s" }
                                """.formatted(section)))
                .andExpect(status().isCreated())
                .andReturn();

        mockMvc.perform(patch("/api/instruments/clauses/{uuid}", uuidAt(result, "$.uuid"))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "body": "%s" }
                                """.formatted(body)))
                .andExpect(status().isOk());
    }

    private void copyTermsTemplateToProperty(String name, String agreementType) throws Exception {
        MvcResult global = mockMvc.perform(post("/api/terms-templates/global")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "%s", "agreementType": "%s" }
                                """.formatted(name, agreementType)))
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

    private static UUID uuidAt(MvcResult result, String path) throws Exception {
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), path));
    }
}

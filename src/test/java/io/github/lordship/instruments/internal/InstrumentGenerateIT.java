package io.github.lordship.instruments.internal;

import com.jayway.jsonpath.JsonPath;
import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.lots.internal.LotRow;
import io.github.lordship.properties.internal.PropertyRow;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/instruments/{uuid}/generate and GET /api/instruments/{uuid}/file.
 *
 * <p>Generated PDFs go to the temp folder (see application-test.properties).
 *
 * <p>An IT rather than a service test because every step writes an audit row,
 * and an audit row needs a logged-in agent.
 */
@Transactional
public class InstrumentGenerateIT extends IntegrationTest {

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
    private UUID template;
    private UUID section;

    @BeforeEach
    void setUp() throws Exception {
        token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        PropertyRow propertyRow = testData.insertProperty("GN");
        LotRow lotRow = testData.insertLot(propertyRow.uuid(), "9");
        TenancyRow tenancyRow = testData.insertTenancy(lotRow.uuid());
        property = propertyRow.uuid();
        tenancy = tenancyRow.uuid();

        permitLand(lotRow.uuid());
        copyLandTermsToProperty();

        template = createDocumentTemplate();
        section = createSection();
    }

    // ---- what generate produces ----------------------------------------------

    @Test
    void generate_shouldStampASerial_andMarkTheDocumentGenerated() throws Exception {
        // Arrange
        UUID lease = readyLease();

        // Act / Assert
        generate(lease)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andExpect(jsonPath("$.serial").value(startsWith("HS-")))
                .andExpect(jsonPath("$.generatedFileId").isNotEmpty())
                .andExpect(jsonPath("$.templateId").value(template.toString()));
    }

    @Test
    void generate_shouldMoveTheChargeTermsToPending() throws Exception {
        // Arrange
        UUID lease = readyLease();

        // Act
        generate(lease).andExpect(status().isOk());

        // Assert -- the deal can no longer change under the printed lease
        mockMvc.perform(get("/api/instruments/{uuid}/charge-terms", lease)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status", everyItem(is("PENDING"))));
    }

    @Test
    void generate_shouldSaveTheWordingAsPrinted() throws Exception {
        // Arrange
        UUID lease = readyLease();

        // Act
        generate(lease).andExpect(status().isOk());

        // Assert -- the printed words and the words with tokens, side by side
        List<String> bodies = jdbc.sql("SELECT body FROM instrument_clause WHERE instrument = :uuid")
                .param("uuid", lease)
                .query(String.class)
                .list();
        List<String> templates = jdbc.sql("SELECT body_template FROM instrument_clause WHERE instrument = :uuid")
                .param("uuid", lease)
                .query(String.class)
                .list();
        assertEquals(List.of("The monthly rent is $650.00."), bodies);
        assertEquals(List.of("The monthly rent is {{term.rate}}."), templates);
    }

    @Test
    void file_shouldReturnTheGeneratedPdf() throws Exception {
        // Arrange
        UUID lease = readyLease();
        generate(lease).andExpect(status().isOk());

        // Act
        MvcResult result = mockMvc.perform(get("/api/instruments/{uuid}/file", lease)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();

        // Assert
        byte[] pdf = result.getResponse().getContentAsByteArray();
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
    }

    // ---- what generate refuses -----------------------------------------------

    @Test
    void generate_shouldReturn400_andChangeNothing_whenTheLeaseIsIncomplete() throws Exception {
        // Arrange -- nothing fills landlord.name on this park
        clause("rent", "Rent is {{term.rate}}, payable to {{landlord.name}}.");
        assignDocument();
        UUID lease = draftWithTerm();

        // Act / Assert
        generate(lease)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("instrument.not_complete"));

        mockMvc.perform(get("/api/instruments/{uuid}", lease)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.serial").doesNotExist());
        mockMvc.perform(get("/api/instruments/{uuid}/charge-terms", lease)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[*].status", everyItem(is("PROPOSED"))));
    }

    @Test
    void generate_shouldReturn400_whenTheLeaseHasNoStartDate() throws Exception {
        // Arrange -- the tenancy has no start date, so the draft has none either
        clause("rent", "The monthly rent is {{term.rate}}.");
        assignDocument();
        UUID lease = createDraft();
        writeSchedule(lease);

        // Act / Assert
        generate(lease)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("instrument.term_start_needed"))
                .andExpect(jsonPath("$.field").value("termStart"));
    }

    @Test
    void generate_shouldReturn409_whenAlreadyGenerated() throws Exception {
        // Arrange -- two clicks make one document
        UUID lease = readyLease();
        generate(lease).andExpect(status().isOk());

        // Act / Assert
        generate(lease)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("instrument.not_editable"));
    }

    @Test
    void generate_shouldReturn404_forAnUnknownDocument() throws Exception {
        // Act / Assert
        generate(UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void file_shouldReturn404_beforeTheDocumentIsGenerated() throws Exception {
        // Arrange
        UUID lease = readyLease();

        // Act / Assert
        mockMvc.perform(get("/api/instruments/{uuid}/file", lease)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    // ---- fixtures ------------------------------------------------------------

    /** A complete lease: one clause, the park's document assigned, dates and schedule set. */
    private UUID readyLease() throws Exception {
        clause("rent", "The monthly rent is {{term.rate}}.");
        assignDocument();
        return draftWithTerm();
    }

    private org.springframework.test.web.servlet.ResultActions generate(UUID lease) throws Exception {
        return mockMvc.perform(post("/api/instruments/{uuid}/generate", lease)
                .header("Authorization", "Bearer " + token));
    }

    private UUID draftWithTerm() throws Exception {
        UUID instrument = createDraft();
        mockMvc.perform(patch("/api/instruments/{uuid}", instrument)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "termStart": "2026-11-01", "termMonths": 12, "onExpiry": "MONTH_TO_MONTH" }
                                """))
                .andExpect(status().isOk());
        writeSchedule(instrument);
        return instrument;
    }

    private void writeSchedule(UUID instrument) throws Exception {
        mockMvc.perform(post("/api/instruments/{uuid}/charge-terms/schedule", instrument)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "steps": [ { "validAt": "2026-11-01", "rate": 650.00 } ] }
                                """))
                .andExpect(status().isCreated());
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

    private UUID createDocumentTemplate() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/document-templates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "GN Land Lease", "agreementType": "LAND", "instrumentType": "LEASE" }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return uuidAt(result, "$.uuid");
    }

    private UUID createSection() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/document-templates/{uuid}/sections", template)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Rent" }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        int index = JsonPath.<List<Object>>read(body, "$.sections").size() - 1;
        return UUID.fromString(JsonPath.read(body, "$.sections[" + index + "].uuid"));
    }

    private void clause(String key, String body) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/document-templates/sections/{uuid}/clauses", section)
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        List<String> ids = JsonPath.read(response,
                "$.sections[?(@.uuid == '" + section + "')].clauses[*].uuid");
        UUID clauseId = UUID.fromString(ids.get(ids.size() - 1));

        mockMvc.perform(patch("/api/document-templates/clauses/{uuid}", clauseId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "clauseKey": "%s", "body": "%s", "ordinal": 10 }
                                """.formatted(key, body)))
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

    private void copyLandTermsToProperty() throws Exception {
        MvcResult global = mockMvc.perform(post("/api/terms-templates/global")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "GN Land Terms", "agreementType": "LAND" }
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

    private void permitLand(UUID lotId) {
        jdbc.sql("""
                        INSERT INTO lot_permissible_agreement_type (lot_id, agreement_type, target_rate, asking_rate)
                        VALUES (:lotId, 'LAND'::agreement_type, :rate, :rate)
                        """)
                .param("lotId", lotId)
                .param("rate", new BigDecimal("650.00"))
                .update();
    }

    private static UUID uuidAt(MvcResult result, String path) throws Exception {
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), path));
    }
}

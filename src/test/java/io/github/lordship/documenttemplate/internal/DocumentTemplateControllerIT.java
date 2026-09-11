package io.github.lordship.documenttemplate.internal;

import com.jayway.jsonpath.JsonPath;
import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP behavior and the paths that need a real database -- creates resolve an
 * acting agent from the audit context, and the seeded WA packet is the only
 * fixture big enough to prove hydration works. The rules themselves are unit
 * tested in {@code DocumentTemplateServiceTest}.
 */
@Transactional
public class DocumentTemplateControllerIT extends IntegrationTest {

    // Seeded by V11. Stable, so the assertions on its shape are meaningful.
    private static final String WA_LEASE = "0199a000-0000-7000-8000-000000000001";

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

    private UUID createTemplate(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/document-templates")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "%s", "agreementType": "LAND", "instrumentType": "LEASE" }
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.uuid"));
    }

    private UUID createSection(UUID templateId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/document-templates/{uuid}/sections", templateId)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(
                JsonPath.read(result.getResponse().getContentAsString(), "$.sections[0].uuid"));
    }

    private UUID createClause(UUID sectionId) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/document-templates/sections/{uuid}/clauses", sectionId)
                                .header("Authorization", "Bearer " + token()))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(
                JsonPath.read(result.getResponse().getContentAsString(), "$.sections[0].clauses[0].uuid"));
    }

    // ---- the seeded packet ---------------------------------------------------

    @Test
    void getTemplate_shouldHydrateTheWholeSeededPacket() throws Exception {
        mockMvc.perform(get("/api/document-templates/{uuid}", WA_LEASE)
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("WA Manufactured Home Lot Lease 2026"))
                .andExpect(jsonPath("$.sections.length()").value(7))
                .andExpect(jsonPath("$.sections[0].sectionKey").value("CHECKLIST"))
                .andExpect(jsonPath("$.sections[6].sectionKey").value("SEPTIC"))
                .andExpect(jsonPath("$.conditionWorklist.length()").value(10)); // changes if we add elements
    }

    // The list view drops children on purpose: sixty clause bodies per row is
    // not what an admin picking a document needs.
    @Test
    void listTemplates_shouldOmitSections() throws Exception {
        mockMvc.perform(get("/api/document-templates")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sections.length()").value(0));
    }

    // The bug that only showed when the filter was omitted: an untyped parameter
    // next to IS NULL gives Postgres nothing to infer from.
    @Test
    void listTemplates_shouldWork_withNoFilters() throws Exception {
        mockMvc.perform(get("/api/document-templates")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk());
    }

    @Test
    void listTemplates_shouldNarrowByAgreementAndInstrumentType() throws Exception {
        String token = token();

        mockMvc.perform(get("/api/document-templates?agreementType=LAND&instrumentType=LEASE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(Matchers.greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/document-templates?agreementType=STORAGE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---- the token picker ----------------------------------------------------

    @Test
    void listTokens_shouldNarrowToTokensThatResolveOnThatDocument() throws Exception {
        String token = token();

        // A notice has no term of its own, so the term tokens drop out
        MvcResult lease = mockMvc.perform(
                        get("/api/document-templates/tokens?instrumentType=LEASE")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult notice = mockMvc.perform(
                        get("/api/document-templates/tokens?instrumentType=INCREASE_NOTICE")
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        int onLease = ((java.util.List<?>) JsonPath.read(
                lease.getResponse().getContentAsString(), "$[*].placeholder")).size();
        int onNotice = ((java.util.List<?>) JsonPath.read(
                notice.getResponse().getContentAsString(), "$[*].placeholder")).size();

        org.junit.jupiter.api.Assertions.assertTrue(onNotice < onLease,
                "a rent increase notice should be offered fewer tokens than a lease");
    }

    @Test
    void listTokens_shouldCarryAllowedValues_onlyForConditionableTokens() throws Exception {
        mockMvc.perform(get("/api/document-templates/tokens")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.placeholder == '{{term.nsf_fee_method}}')].allowedValues[*]")
                        .value(Matchers.hasItems("NONE", "FLAT", "BANK_OR_FLAT")))
                .andExpect(jsonPath("$[?(@.placeholder == '{{term.rate}}')].canCondition")
                        .value(Matchers.hasItem(false)));
    }

    // ---- authoring -----------------------------------------------------------

    @Test
    void createSection_shouldAssignAnOrdinal_withoutBeingGivenOne() throws Exception {
        UUID templateId = createTemplate("Ordinal scratch");

        mockMvc.perform(post("/api/document-templates/{uuid}/sections", templateId)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"First\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sections[0].ordinal").value(Matchers.notNullValue()));
    }

    // Add clause is a button, not a form: every column a clause carries is
    // nullable, so an empty POST is the whole request.
    @Test
    void createClause_shouldAcceptNoBodyAtAll() throws Exception {
        UUID templateId = createTemplate("Clause scratch");
        UUID sectionId = createSection(templateId, "Scratch section");

        mockMvc.perform(post("/api/document-templates/sections/{uuid}/clauses", sectionId)
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sections[0].clauses.length()").value(1))
                .andExpect(jsonPath("$.sections[0].clauses[0].body").doesNotExist());
    }

    @Test
    void patchClause_shouldReturn400_withASuggestion_forAnUnknownToken() throws Exception {
        UUID templateId = createTemplate("Token scratch");
        UUID sectionId = createSection(templateId, "Scratch section");
        UUID clauseId = createClause(sectionId);

        mockMvc.perform(patch("/api/document-templates/clauses/{uuid}", clauseId)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Rent is {{term.raet}}.\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("term.rate")));
    }

    @Test
    void patchClause_shouldRoundTripTheConditionArray() throws Exception {
        UUID templateId = createTemplate("Condition scratch");
        UUID sectionId = createSection(templateId, "Scratch section");
        UUID clauseId = createClause(sectionId);
        String token = token();

        mockMvc.perform(patch("/api/document-templates/clauses/{uuid}", clauseId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "conditionField": "term.nsf_fee_method",
                                  "conditionValues": ["FLAT", "BANK_OR_FLAT"] }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].clauses[0].conditionValues[*]")
                        .value(Matchers.hasItems("FLAT", "BANK_OR_FLAT")))
                .andExpect(jsonPath("$.sections[0].conditionCoverage[0].uncovered[*]")
                        .value(Matchers.hasItem("NONE")));

        // Clearing takes both keys, so a half-cleared condition cannot exist
        mockMvc.perform(patch("/api/document-templates/clauses/{uuid}", clauseId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditionField\": null, \"conditionValues\": []}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].clauses[0].conditionField").doesNotExist());
    }

    @Test
    void patchClause_shouldReportAnUnguardedAmount() throws Exception {
        UUID templateId = createTemplate("Unguarded scratch");
        UUID sectionId = createSection(templateId, "Scratch section");
        UUID clauseId = createClause(sectionId);

        mockMvc.perform(patch("/api/document-templates/clauses/{uuid}", clauseId)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"A late fee of {{term.late_fee_amount}} applies.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].clauses[0].unguardedTokens[*]")
                        .value(Matchers.hasItem("term.late_fee_amount")));
    }

    @Test
    void patchSection_shouldBumpTheTemplateVersion() throws Exception {
        UUID templateId = createTemplate("Version scratch");
        UUID sectionId = createSection(templateId, "Scratch section");

        mockMvc.perform(patch("/api/document-templates/sections/{uuid}", sectionId)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signatureBlock\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(Matchers.greaterThan(1)))
                .andExpect(jsonPath("$.sections[0].signatureBlock").value(true));
    }

    // ---- preview -------------------------------------------------------------

    @Test
    void preview_shouldPrintOnlyTheMatchingBranch() throws Exception {
        UUID templateId = createTemplate("Preview scratch");
        UUID sectionId = createSection(templateId, "Scratch section");
        UUID clauseId = createClause(sectionId);
        String token = token();

        mockMvc.perform(patch("/api/document-templates/clauses/{uuid}", clauseId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "body": "A returned payment incurs {{term.nsf_fee_amount}}.",
                                  "conditionField": "term.nsf_fee_method",
                                  "conditionValues": ["FLAT"] }
                                """))
                .andExpect(status().isOk());

        // The matching value prints it
        mockMvc.perform(post("/api/document-templates/{uuid}/preview", templateId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"methodValues\": {\"term.nsf_fee_method\": \"FLAT\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.length()").value(1))
                .andExpect(jsonPath("$.skipped.length()").value(0));

        // Another value holds it back, and says which
        mockMvc.perform(post("/api/document-templates/{uuid}/preview", templateId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"methodValues\": {\"term.nsf_fee_method\": \"NONE\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.length()").value(0))
                .andExpect(jsonPath("$.skipped[0].reason").value(Matchers.containsString("NONE")));
    }

    // The NPE this endpoint shipped with: an empty map means every method is
    // unset, and List.of(...) throws on a null probe rather than answering false.
    @Test
    void preview_shouldNotFail_whenNoMethodValuesAreSupplied() throws Exception {
        mockMvc.perform(post("/api/document-templates/{uuid}/preview", WA_LEASE)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"methodValues\": {}}"))
                .andExpect(status().isOk());
    }

    @Test
    void preview_shouldNotFail_withNoBodyAtAll() throws Exception {
        mockMvc.perform(post("/api/document-templates/{uuid}/preview", WA_LEASE)
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk());
    }

    @Test
    void preview_shouldReturn400_forAValueTheColumnDoesNotTake() throws Exception {
        mockMvc.perform(post("/api/document-templates/{uuid}/preview", WA_LEASE)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"methodValues\": {\"term.trash_method\": \"SUBMETERED\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("SUBMETERED")));
    }

    @Test
    void preview_shouldReturn404_forAnUnknownTemplate() throws Exception {
        mockMvc.perform(post("/api/document-templates/{uuid}/preview", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"methodValues\": {}}"))
                .andExpect(status().isNotFound());
    }

    // ---- delete and auth -----------------------------------------------------

    @Test
    void deleteTemplate_shouldReturn204_thenReturn404() throws Exception {
        UUID templateId = createTemplate("Disposable");
        String token = token();

        mockMvc.perform(delete("/api/document-templates/{uuid}", templateId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/document-templates/{uuid}", templateId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void getTemplate_shouldReturn404_forAnUnknownUuid() throws Exception {
        mockMvc.perform(get("/api/document-templates/{uuid}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void endpoints_shouldReturn401_whenNoTokenProvided() throws Exception {
        mockMvc.perform(get("/api/document-templates"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/document-templates/tokens"))
                .andExpect(status().isUnauthorized());
    }
}
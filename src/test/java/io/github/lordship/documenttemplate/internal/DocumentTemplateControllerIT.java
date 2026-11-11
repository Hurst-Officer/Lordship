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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
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

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private UUID createSection(UUID templateId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/document-templates/{uuid}/sections", templateId)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(
                JsonPath.read(result.getResponse().getContentAsString(), "$.sections[0].id"));
    }

    private UUID createClause(UUID sectionId) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/document-templates/sections/{uuid}/clauses", sectionId)
                                .header("Authorization", "Bearer " + token()))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(
                JsonPath.read(result.getResponse().getContentAsString(), "$.sections[0].clauses[0].id"));
    }

    // ---- the seeded packet ---------------------------------------------------

    @Test
    void getTemplate_shouldHydrateTheWholeSeededPacket() throws Exception {
        mockMvc.perform(get("/api/document-templates/{uuid}", WA_LEASE)
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("WA Manufactured Home Lot Lease 2026"))
                .andExpect(jsonPath("$.sections.length()").value(8))
                .andExpect(jsonPath("$.sections[0].sectionKey").value("CHECKLIST"))
                .andExpect(jsonPath("$.sections[7].sectionKey").value("SEPTIC"))
                .andExpect(jsonPath("$.conditionWorklist.length()").value(11)); // changes if we add elements
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

    // ---- moving --------------------------------------------------------------
    // A move names where the clause goes (under parentId, directly after afterId) and the server picks the
    // ordinal. Every answer is the whole document with the section's clauses in ordinal order.

    @Test
    void moveClause_shouldPutAClauseFirst_last_andBetweenTwo() throws Exception {
        UUID sectionId = createSection(createTemplate("Move scratch"), "Rules");
        UUID a = addClause(sectionId);
        UUID b = addClause(sectionId);
        UUID c = addClause(sectionId);

        // first: no clause to come after
        moveClause(c, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].clauses[*].id").value(Matchers.contains(id(c), id(a), id(b))));
        // last: after the last of the others
        moveClause(c, null, b)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].clauses[*].id").value(Matchers.contains(id(a), id(b), id(c))));
        // between two: after the first of the two
        moveClause(c, null, a)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].clauses[*].id").value(Matchers.contains(id(a), id(c), id(b))));
    }

    // Ordinals keep four decimals. With no number left between two neighbours, the section's clauses are spread
    // out to 10, 20, 30 in the order the clauses had (renumberSection), and the move then takes the middle.
    @Test
    void moveClause_shouldRenumberTheSection_whenTwoNeighboursLeaveNoRoom() throws Exception {
        UUID sectionId = createSection(createTemplate("Renumber scratch"), "Rules");
        UUID a = addClause(sectionId);
        UUID b = addClause(sectionId);
        UUID c = addClause(sectionId);
        // clause a sits at 1; clause b now sits one ten-thousandth after clause a
        mockMvc.perform(patch("/api/document-templates/clauses/{uuid}", b)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ordinal\": 1.0001}"))
                .andExpect(status().isOk());

        moveClause(c, null, a)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].clauses[*].id").value(Matchers.contains(id(a), id(c), id(b))))
                .andExpect(jsonPath("$.sections[0].clauses[0].ordinal").value(10.0))
                .andExpect(jsonPath("$.sections[0].clauses[1].ordinal").value(15.0))
                .andExpect(jsonPath("$.sections[0].clauses[2].ordinal").value(20.0));
    }

    @Test
    void moveClause_shouldRefuseAMove_thatPutsAClauseUnderItsOwnSubClause() throws Exception {
        UUID sectionId = createSection(createTemplate("Cycle scratch"), "Rules");
        UUID a = addClause(sectionId);
        UUID b = addClause(sectionId);
        // clause b goes under clause a
        moveClause(b, a, null).andExpect(status().isOk());

        // clause a under clause b would make each the parent of the other
        moveClause(a, b, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("clause.not_saved"))
                .andExpect(jsonPath("$.problems[*].code").value(Matchers.hasItem("clause.parent_cycle")));
    }

    @Test
    void moveSection_shouldPutASectionDirectlyAfterAnother() throws Exception {
        UUID templateId = createTemplate("Section move scratch");
        UUID lease = addSection(templateId, "Lease");
        UUID rules = addSection(templateId, "Rules");
        UUID pets = addSection(templateId, "Pets");

        mockMvc.perform(post("/api/document-templates/sections/{uuid}/move", lease)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"afterId\": \"" + rules + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[*].id").value(Matchers.contains(id(rules), id(lease), id(pets))));
    }

    // ---- starting from another document ---------------------------------------
    // What each copied link points at is DocumentCopyRepositoryTest's; this is the call itself, on the seeded packet.

    @Test
    void createTemplate_shouldStartFromAnotherDocumentsWording() throws Exception {
        String token = token();
        MvcResult original = mockMvc.perform(get("/api/document-templates/{uuid}", WA_LEASE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String originalSection = JsonPath.read(original.getResponse().getContentAsString(), "$.sections[0].id");

        mockMvc.perform(post("/api/document-templates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "WA Lot Lease 2027", "agreementType": "LAND", "instrumentType": "LEASE",
                                  "copyFromId": "%s" }
                                """.formatted(WA_LEASE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("WA Lot Lease 2027"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.sections.length()").value(8))
                .andExpect(jsonPath("$.sections[0].sectionKey").value("CHECKLIST"))
                // the copy's sections are new rows, not the original's
                .andExpect(jsonPath("$.sections[0].id").value(Matchers.not(originalSection)));
    }

    // The id of the section a POST .../sections just added. A new section lands last.
    private UUID addSection(UUID templateId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/document-templates/{uuid}/sections", templateId)
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        List<String> ids = JsonPath.read(result.getResponse().getContentAsString(), "$.sections[*].id");
        return UUID.fromString(ids.get(ids.size() - 1));
    }

    // The id of the clause a POST .../clauses just added. A new clause lands last in the section.
    private UUID addClause(UUID sectionId) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/api/document-templates/sections/{uuid}/clauses", sectionId)
                                .header("Authorization", "Bearer " + token()))
                .andExpect(status().isCreated())
                .andReturn();

        List<String> ids = JsonPath.read(result.getResponse().getContentAsString(), "$.sections[0].clauses[*].id");
        return UUID.fromString(ids.get(ids.size() - 1));
    }

    // POST .../clauses/{id}/move. A null parentId is the top level; a null afterId is first under that parent.
    private ResultActions moveClause(UUID clauseId, UUID parentId, UUID afterId) throws Exception {
        return mockMvc.perform(post("/api/document-templates/clauses/{uuid}/move", clauseId)
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentId\": " + quoted(parentId) + ", \"afterId\": " + quoted(afterId) + "}"));
    }

    private static String quoted(UUID id) {
        return id == null ? "null" : "\"" + id + "\"";
    }

    private static String id(UUID id) {
        return id.toString();
    }
}
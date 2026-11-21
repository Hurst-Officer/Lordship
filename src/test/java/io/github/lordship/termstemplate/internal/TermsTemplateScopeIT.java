package io.github.lordship.termstemplate.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.TestAuthSupport.TestAgent;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.SystemPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// /api/terms-templates follows PropertyScope, like the rest of a park's records (PropertyScopeIT).
// Two parks copied the same land template, so each park has a deal. The agent is assigned to park A only and
// holds every terms_template permission the calls below need, so each refusal comes from the scope and not
// from a missing permission.
// Every deal of park B asked for here exists: without the scope check each call would answer 200, 201 or 204.
@Transactional
public class TermsTemplateScopeIT extends IntegrationTest {

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TermsTemplateRepository termsTemplateRepository;

    private String token;
    private UUID parkA;
    private UUID parkB;
    // the global land template both parks copied
    private UUID templateId;
    // a global storage template neither park has copied
    private UUID storageTemplateId;
    private UUID dealA;
    private UUID dealB;

    @BeforeEach
    void setUp() throws Exception {
        String rootToken = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken,
                "terms_template:view", "terms_template:edit", "terms_template:delete",
                "terms_template:manage_global");

        parkA = testData.insertProperty("QA").uuid();
        parkB = testData.insertProperty("QB").uuid();

        // Fixtures go in through the repository, as in TermsTemplateRepositoryTest
        TermsTemplateRow template = termsTemplateRepository.save(new TermsTemplateRow(
                null, "Scope Land Terms", AgreementType.LAND, SystemPrincipal.AGENT_UUID));
        templateId = template.uuid();
        storageTemplateId = termsTemplateRepository.save(new TermsTemplateRow(
                null, "Scope Storage Terms", AgreementType.STORAGE, SystemPrincipal.AGENT_UUID)).uuid();
        dealA = termsTemplateRepository.saveCopy(template.copyTo(parkA, SystemPrincipal.AGENT_UUID)).uuid();
        dealB = termsTemplateRepository.saveCopy(template.copyTo(parkB, SystemPrincipal.AGENT_UUID)).uuid();

        mockMvc.perform(post("/api/property-assignments/assign")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "agentId": "%s", "propertyId": "%s" }
                                """.formatted(agent.uuid(), parkA)))
                .andExpect(status().isCreated());
        // the agent's scope is read again on their next request
        authorizationCache.invalidateAll();

        token = agent.token();
    }

    @Test
    void readsOfOtherParksDeal_are404() throws Exception {
        mockMvc.perform(get("/api/terms-templates").param("propertyId", parkB.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/terms-templates/" + dealB)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void changingOtherParksDeal_is404_andLeavesTheDealAsItWas() throws Exception {
        patchOne(dealB, """
                { "gracePeriodDays": 3 }
                """)
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/terms-templates/" + dealB)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        // a restore of a live deal answers 200 inside the scope
        mockMvc.perform(post("/api/terms-templates/" + dealB + "/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        TermsTemplateRow deal = termsTemplateRepository.findById(dealB).orElseThrow();
        assertEquals(7, deal.gracePeriodDays(), "the deal keeps the grace period the deal was copied with");
        assertNull(deal.deletedAt(), "the deal is not deleted");
    }

    @Test
    void deletedDealOfOtherPark_cannotBeBroughtBack() throws Exception {
        termsTemplateRepository.softDelete(dealB);

        mockMvc.perform(post("/api/terms-templates/" + dealB + "/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        assertTrue(termsTemplateRepository.findById(dealB).isEmpty(), "the deal stays deleted");
    }

    @Test
    void copyingATemplateIntoOtherPark_is404() throws Exception {
        copyInto(parkB)
                .andExpect(status().isNotFound());
        assertTrue(termsTemplateRepository.findByPropertyAndAgreementType(parkB, AgreementType.STORAGE).isEmpty(),
                "park B was given no storage deal");

        // the same copy into the agent's own park goes through
        copyInto(parkA)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.propertyId").value(parkA.toString()));
    }

    @Test
    void aNewTemplateFromOtherParksDeal_is404() throws Exception {
        // a new template made from a deal carries the deal's terms, so the call reads the deal
        newTemplateFrom("Scope Lifted Terms", dealB)
                .andExpect(status().isNotFound());
        assertTrue(termsTemplateRepository.findGlobalByName("Scope Lifted Terms").isEmpty(),
                "no template was made from park B's deal");
    }

    @Test
    void severalDealsWithOneOfOtherPark_areRefusedAsGone_andNoDealIsChanged() throws Exception {
        // the answer a deleted deal gets, so the call does not tell the agent that park B has a deal
        patchMany("""
                { "ids": ["%s", "%s"], "changes": { "gracePeriodDays": 3 } }
                """.formatted(dealA, dealB))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("terms_template.gone"));

        // refused before any deal is written to, the agent's own deal included
        assertEquals(7, termsTemplateRepository.findById(dealA).orElseThrow().gracePeriodDays());
        assertEquals(7, termsTemplateRepository.findById(dealB).orElseThrow().gracePeriodDays());
    }

    @Test
    void ownParksDeal_canStillBeReadAndChanged() throws Exception {
        mockMvc.perform(get("/api/terms-templates").param("propertyId", parkA.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(dealA.toString()));
        mockMvc.perform(get("/api/terms-templates/" + dealA)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        patchOne(dealA, """
                { "gracePeriodDays": 5 }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gracePeriodDays").value(5));
        patchMany("""
                { "ids": ["%s"], "changes": { "gracePeriodDays": 4 } }
                """.formatted(dealA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(dealA.toString()))
                .andExpect(jsonPath("$[0].gracePeriodDays").value(4));

        newTemplateFrom("Scope Own Terms", dealA)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gracePeriodDays").value(4));

        mockMvc.perform(delete("/api/terms-templates/" + dealA)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/terms-templates/" + dealA + "/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dealA.toString()));
    }

    @Test
    void globalTemplate_isOutsideTheQuestion() throws Exception {
        // a global template belongs to no park: the permissions alone decide
        mockMvc.perform(get("/api/terms-templates/" + templateId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        patchOne(templateId, """
                { "gracePeriodDays": 6 }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gracePeriodDays").value(6));
        patchMany("""
                { "ids": ["%s", "%s"], "changes": { "gracePeriodDays": 8 } }
                """.formatted(templateId, dealA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void templatesScreen_listsOnlyTheDealsOfAssignedParks() throws Exception {
        mockMvc.perform(get("/api/screens/rates/templates")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deals[*].deal.id", hasItem(dealA.toString())))
                .andExpect(jsonPath("$.deals[*].deal.id", not(hasItem(dealB.toString()))))
                .andExpect(jsonPath("$.templates[*].id", hasItem(templateId.toString())));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResultActions patchOne(UUID uuid, String body) throws Exception {
        return mockMvc.perform(patch("/api/terms-templates/" + uuid)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions patchMany(String body) throws Exception {
        return mockMvc.perform(patch("/api/terms-templates")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // Copies the storage template, which neither park has, into the property
    private ResultActions copyInto(UUID propertyId) throws Exception {
        return mockMvc.perform(post("/api/terms-templates/" + storageTemplateId + "/copy")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "propertyId": "%s" }
                        """.formatted(propertyId)));
    }

    private ResultActions newTemplateFrom(String name, UUID copyFromId) throws Exception {
        return mockMvc.perform(post("/api/terms-templates/global")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "name": "%s", "agreementType": "LAND", "copyFromId": "%s" }
                        """.formatted(name, copyFromId)));
    }
}

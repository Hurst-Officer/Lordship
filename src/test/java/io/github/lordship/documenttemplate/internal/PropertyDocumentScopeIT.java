package io.github.lordship.documenttemplate.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
import io.github.lordship.TestAuthSupport.TestAgent;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// /api/property-documents follows PropertyScope, like the rest of a park's records (PropertyScopeIT).
// Two parks use the same lease. The agent is assigned to park A only and holds every property_document
// permission, so each 404 below comes from the scope and not from a missing permission.
// Every row of park B asked for here exists and is live: without the scope check each call would answer
// 200, 201, 204 or 409, never 404.
@Transactional
public class PropertyDocumentScopeIT extends IntegrationTest {

    // The SYSTEM agent seeded in V10: who the fixtures below are created by.
    static final UUID SYSTEM_AGENT = UUID.fromString("00000000-0000-7000-8000-000000000002");

    @Value("${lordship.root.email}")
    private String rootEmail;

    @Value("${lordship.root.password}")
    private String rootPassword;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    DocumentTemplateRepository documentTemplateRepository;

    @Autowired
    DocumentSectionRepository documentSectionRepository;

    @Autowired
    TemplateClauseRepository templateClauseRepository;

    @Autowired
    PropertyDocumentAssignmentRepository assignmentRepository;

    @Autowired
    PropertyDocumentCustomizationRepository customizationRepository;

    private String token;
    private UUID parkA;
    private UUID parkB;
    private UUID templateId;
    private UUID sectionId;
    private UUID clauseId;
    private UUID assignmentA;
    private UUID assignmentB;
    // a clause of park B's own
    private UUID customizationB;

    @BeforeEach
    void setUp() throws Exception {
        String rootToken = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);
        TestAgent agent = TestAuthSupport.agentWithPermissions(mockMvc, objectMapper, rootToken,
                "property_document:view", "property_document:assign", "property_document:unassign");

        parkA = testData.insertProperty("DA").uuid();
        parkB = testData.insertProperty("DB").uuid();

        // Fixtures go in through the repositories, as in the repository tests of this package
        DocumentTemplateRow template = documentTemplateRepository.save(
                "Scope lease", AgreementType.LAND, InstrumentType.LEASE, SYSTEM_AGENT);
        templateId = template.uuid();
        sectionId = documentSectionRepository.save(templateId, "Rules", SYSTEM_AGENT).uuid();
        clauseId = templateClauseRepository.save(sectionId, SYSTEM_AGENT).uuid();
        assignmentA = assignmentRepository.save(parkA, templateId,
                template.agreementType(), template.instrumentType(), SYSTEM_AGENT).uuid();
        assignmentB = assignmentRepository.save(parkB, templateId,
                template.agreementType(), template.instrumentType(), SYSTEM_AGENT).uuid();
        customizationB = customizationRepository.addClause(assignmentB, sectionId, SYSTEM_AGENT).uuid();

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
    void readsOfOtherPark_are404() throws Exception {
        mockMvc.perform(get("/api/property-documents").param("propertyId", parkB.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/property-documents/resolve")
                        .param("propertyId", parkB.toString())
                        .param("agreementType", "LAND")
                        .param("instrumentType", "LEASE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/property-documents/audit").param("propertyId", parkB.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/property-documents/" + assignmentB)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void assigningAndEndingAtOtherPark_are404() throws Exception {
        mockMvc.perform(post("/api/property-documents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "propertyId": "%s", "documentTemplateId": "%s" }
                                """.formatted(parkB, templateId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/property-documents/" + assignmentB)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "note": "not mine to write" }
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/property-documents/" + assignmentB)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        // a restore of a live assignment answers 200 inside the scope
        mockMvc.perform(post("/api/property-documents/" + assignmentB + "/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void changingWhatOtherParkPrints_is404() throws Exception {
        mockMvc.perform(post("/api/property-documents/" + assignmentB + "/exclusions/sections")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "sectionId": "%s" }
                                """.formatted(sectionId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/property-documents/" + assignmentB + "/exclusions/clauses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "clauseId": "%s" }
                                """.formatted(clauseId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/property-documents/" + assignmentB + "/clauses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "sectionId": "%s" }
                                """.formatted(sectionId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/property-documents/customizations/" + customizationB)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "title": "not mine to write" }
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/property-documents/customizations/" + customizationB)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        // a restore of a live change answers 200 inside the scope
        mockMvc.perform(post("/api/property-documents/customizations/" + customizationB + "/restore")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownPark_canStillBeReadAndChanged() throws Exception {
        mockMvc.perform(get("/api/property-documents").param("propertyId", parkA.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(assignmentA.toString()));
        mockMvc.perform(post("/api/property-documents/" + assignmentA + "/exclusions/clauses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "clauseId": "%s" }
                                """.formatted(clauseId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customizations.length()").value(1))
                .andExpect(jsonPath("$.customizations[0].action").value("EXCLUDE_CLAUSE"))
                .andExpect(jsonPath("$.customizations[0].clauseId").value(clauseId.toString()));
    }
}

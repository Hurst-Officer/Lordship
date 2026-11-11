package io.github.lordship.documenttemplate.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.TestAuthSupport;
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

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// What a park may not do to a document the park uses, each of which would stop the park's leases printing:
// a clause of the park's own without a position, a citation of a clause the park leaves out, and leaving out
// a clause the park's own clause cites. One park, one lease with two clauses (Septic and Pets), and one clause
// of the park's own.
@Transactional
public class PropertyDocumentChangesIT extends IntegrationTest {

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
    private UUID assignmentId;
    private UUID septicId;
    // the clause of the park's own
    private UUID parkClauseId;

    @BeforeEach
    void setUp() throws Exception {
        token = TestAuthSupport.loginAsRoot(mockMvc, objectMapper, rootEmail, rootPassword);

        // Fixtures go in through the repositories, as in the repository tests of this package
        DocumentTemplateRow template = documentTemplateRepository.save(
                "Changes lease", AgreementType.LAND, InstrumentType.LEASE, SYSTEM_AGENT);
        UUID sectionId = documentSectionRepository.save(template.uuid(), "Rules", SYSTEM_AGENT).uuid();
        septicId = templateClauseRepository.save(sectionId, SYSTEM_AGENT).uuid();
        templateClauseRepository.patch(septicId, Map.of("title", "Septic", "body", "The tank is pumped yearly."));
        UUID petsId = templateClauseRepository.save(sectionId, SYSTEM_AGENT).uuid();
        templateClauseRepository.patch(petsId, Map.of("title", "Pets", "body", "Two pets at most."));

        UUID propertyId = testData.insertProperty("DC").uuid();
        assignmentId = assignmentRepository.save(propertyId, template.uuid(),
                template.agreementType(), template.instrumentType(), SYSTEM_AGENT).uuid();
        parkClauseId = customizationRepository.addClause(assignmentId, sectionId, SYSTEM_AGENT).uuid();
    }

    // DocumentFreeze sorts a section's clauses by ordinal, so a park clause with no ordinal broke every lease
    @Test
    void patchCustomization_shouldRefuseAnEmptyOrdinal() throws Exception {
        mockMvc.perform(patch("/api/property-documents/customizations/" + parkClauseId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "ordinal": null }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("customization.ordinal_required"))
                .andExpect(jsonPath("$.field").value("ordinal"));
    }

    @Test
    void patchCustomization_shouldRefuseWordsThatCiteAClauseTheParkLeavesOut() throws Exception {
        // Arrange -- the park leaves Septic out
        customizationRepository.excludeClause(assignmentId, septicId, SYSTEM_AGENT);

        // Act / Assert
        mockMvc.perform(patch("/api/property-documents/customizations/" + parkClauseId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "body": "Pumping, see {{ref:%s}}." }
                                """.formatted(septicId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("customization.not_saved"))
                .andExpect(jsonPath("$.problems[0].code").value("customization.cites_left_out"))
                .andExpect(jsonPath("$.problems[0].field").value("body"));
    }

    @Test
    void excludeClause_shouldRefuseAClauseTheParksOwnClauseCites() throws Exception {
        // Arrange -- the park's own clause cites Septic
        customizationRepository.patch(parkClauseId, Map.of("body", "Pumping, see {{ref:" + septicId + "}}."));

        // Act / Assert
        mockMvc.perform(post("/api/property-documents/" + assignmentId + "/exclusions/clauses")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "clauseId": "%s" }
                                """.formatted(septicId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("customization.clause_is_cited"));
    }
}

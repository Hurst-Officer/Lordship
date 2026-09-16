package io.github.lordship.documenttemplate.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.documenttemplate.CustomizationAction;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
public class PropertyDocumentCustomizationRepositoryTest extends IntegrationTest {

    // The SYSTEM agent seeded in V10, which is what V11 attributes its rows to.
    static final UUID SYSTEM_AGENT = UUID.fromString("00000000-0000-7000-8000-000000000002");

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

    // Fixtures are built through other modules' repositories, never their
    // services: a service call pulls in the audit write, which has no principal
    // to attribute to here.
    private DocumentTemplateRow leaseTemplate(String name) {
        return documentTemplateRepository.save(
                name, AgreementType.LAND, InstrumentType.LEASE, SYSTEM_AGENT);
    }

    private UUID section(DocumentTemplateRow template, String name) {
        return documentSectionRepository.save(template.uuid(), name, SYSTEM_AGENT).uuid();
    }

    private TemplateClauseRow clause(UUID sectionId, String ordinal) {
        TemplateClauseRow saved = templateClauseRepository.save(sectionId, SYSTEM_AGENT);
        return templateClauseRepository
                .patch(saved.uuid(), Map.of("ordinal", new BigDecimal(ordinal)))
                .orElseThrow();
    }

    private UUID assignment(String propertyCode, DocumentTemplateRow template) {
        UUID propertyId = testData.insertProperty(propertyCode).uuid();
        return assignmentRepository.save(propertyId, template.uuid(),
                template.agreementType(), template.instrumentType(), SYSTEM_AGENT).uuid();
    }

    private Set<UUID> uuids(List<PropertyDocumentCustomizationRow> rows) {
        return rows.stream().map(PropertyDocumentCustomizationRow::uuid).collect(Collectors.toSet());
    }

    // ---- excluding -----------------------------------------------------------

    @Test
    void excludeSection_shouldRecordTheSectionAndNothingElse() {
        // Arrange -- this park is on city sewer
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID septic = section(template, "Septic Addendum");
        UUID assignment = assignment("C001", template);

        // Act
        PropertyDocumentCustomizationRow saved =
                customizationRepository.excludeSection(assignment, septic, SYSTEM_AGENT);

        // Assert -- an exclusion names a target and says "not this one"; the
        // clause columns mean nothing for it
        assertNotNull(saved.uuid());
        assertEquals(CustomizationAction.EXCLUDE_SECTION, saved.action());
        assertEquals(septic, saved.section());
        assertNull(saved.clause());
        assertNull(saved.ordinal());
        assertNull(saved.body());
        assertEquals(List.of(), saved.conditionValues());
        assertNotNull(saved.createdAt());
        assertNull(saved.deletedAt());
    }

    @Test
    void excludeClause_shouldRecordTheClauseAndNothingElse() {
        // Arrange
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID rules = section(template, "Rules");
        TemplateClauseRow quietHours = clause(rules, "20");
        UUID assignment = assignment("C002", template);

        // Act
        PropertyDocumentCustomizationRow saved =
                customizationRepository.excludeClause(assignment, quietHours.uuid(), SYSTEM_AGENT);

        // Assert
        assertEquals(CustomizationAction.EXCLUDE_CLAUSE, saved.action());
        assertEquals(quietHours.uuid(), saved.clause());
        assertNull(saved.section());
    }

    @Test
    void findExclusion_shouldFindOne_whicheverColumnNamesTheTarget() {
        // Arrange -- section and clause exclusions live in different columns,
        // and the duplicate check must not care which
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID rules = section(template, "Rules");
        TemplateClauseRow parking = clause(rules, "10");
        UUID assignment = assignment("C003", template);

        customizationRepository.excludeSection(assignment, rules, SYSTEM_AGENT);
        customizationRepository.excludeClause(assignment, parking.uuid(), SYSTEM_AGENT);

        // Act + Assert
        assertTrue(customizationRepository.findExclusion(assignment, rules).isPresent());
        assertTrue(customizationRepository.findExclusion(assignment, parking.uuid()).isPresent());
        assertTrue(customizationRepository.findExclusion(assignment, UUID.randomUUID()).isEmpty());
    }

    @Test
    void findExclusion_shouldNotSeeAnotherParksExclusion() {
        // Arrange -- two parks, same document, one of them drops the section
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID septic = section(template, "Septic Addendum");
        UUID onCitySewer = assignment("C004", template);
        UUID onSeptic = assignment("C005", template);

        customizationRepository.excludeSection(onCitySewer, septic, SYSTEM_AGENT);

        // Act + Assert -- the other park still has its septic addendum
        assertTrue(customizationRepository.findExclusion(onSeptic, septic).isEmpty());
    }

    // ---- adding --------------------------------------------------------------

    @Test
    void addClause_shouldPersistAnEmptyClause_withOnlyItsSection() {
        // Arrange
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID rules = section(template, "Rules");
        UUID assignment = assignment("C006", template);

        // Act -- the body is a patch away, so "add clause" is a button
        PropertyDocumentCustomizationRow saved =
                customizationRepository.addClause(assignment, rules, SYSTEM_AGENT);

        // Assert
        assertEquals(CustomizationAction.ADD_CLAUSE, saved.action());
        assertEquals(rules, saved.section());
        assertNull(saved.clause());
        assertNull(saved.title());
        assertNull(saved.body());
        assertNotNull(saved.ordinal());
    }

    @Test
    void addClause_shouldLandAfterTheTemplatesLastClauseInThatSection() {
        // Arrange -- template clauses at 10 and 20
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID rules = section(template, "Rules");
        clause(rules, "10");
        clause(rules, "20");
        UUID assignment = assignment("C007", template);

        // Act
        PropertyDocumentCustomizationRow saved =
                customizationRepository.addClause(assignment, rules, SYSTEM_AGENT);

        // Assert -- both tables share one ordinal space, so counting only the
        // customizations would have put this park's rule on top of clause 10
        assertEquals(0, new BigDecimal("21").compareTo(saved.ordinal()));
    }

    @Test
    void addClause_shouldLandAfterThisParksOwnPreviousClause() {
        // Arrange
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID rules = section(template, "Rules");
        clause(rules, "10");
        UUID assignment = assignment("C008", template);

        // Act
        customizationRepository.addClause(assignment, rules, SYSTEM_AGENT);
        PropertyDocumentCustomizationRow second =
                customizationRepository.addClause(assignment, rules, SYSTEM_AGENT);

        // Assert -- two rules added in a row do not collide on one number
        assertEquals(0, new BigDecimal("12").compareTo(second.ordinal()));
    }

    @Test
    void addClause_shouldIgnoreAnotherParksClauses_whenChoosingItsOrdinal() {
        // Arrange -- the ordinal space is per park, not global
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID rules = section(template, "Rules");
        clause(rules, "10");
        UUID first = assignment("C009", template);
        UUID second = assignment("C010", template);

        customizationRepository.addClause(first, rules, SYSTEM_AGENT);
        customizationRepository.addClause(first, rules, SYSTEM_AGENT);

        // Act
        PropertyDocumentCustomizationRow theirs =
                customizationRepository.addClause(second, rules, SYSTEM_AGENT);

        // Assert
        assertEquals(0, new BigDecimal("11").compareTo(theirs.ordinal()));
    }

    // ---- patching ------------------------------------------------------------

    @Test
    void patch_shouldWriteTheBodyAndAConditionTogether() {
        // Arrange
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID rules = section(template, "Rules");
        UUID assignment = assignment("C011", template);
        PropertyDocumentCustomizationRow saved =
                customizationRepository.addClause(assignment, rules, SYSTEM_AGENT);

        // Act
        PropertyDocumentCustomizationRow patched = customizationRepository.patch(
                saved.uuid(),
                Map.of("body", "No boats on the lot.",
                        "condition_field", "term.water_method",
                        "condition_values", List.of("FLAT", "RUBS"))).orElseThrow();

        // Assert
        assertEquals("No boats on the lot.", patched.body());
        assertEquals("term.water_method", patched.conditionField());
        assertEquals(List.of("FLAT", "RUBS"), patched.conditionValues());
    }

    @Test
    void patch_shouldStoreAnEmptyConditionAsNoCondition() {
        // Arrange -- an empty list clears the condition rather than storing {}
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID rules = section(template, "Rules");
        UUID assignment = assignment("C012", template);
        PropertyDocumentCustomizationRow saved =
                customizationRepository.addClause(assignment, rules, SYSTEM_AGENT);

        customizationRepository.patch(saved.uuid(),
                Map.of("condition_field", "term.water_method",
                        "condition_values", List.of("FLAT")));

        // Act
        java.util.Map<String, Object> clearing = new java.util.HashMap<>();
        clearing.put("condition_field", null);
        clearing.put("condition_values", List.of());
        PropertyDocumentCustomizationRow cleared =
                customizationRepository.patch(saved.uuid(), clearing).orElseThrow();

        // Assert
        assertNull(cleared.conditionField());
        assertEquals(List.of(), cleared.conditionValues());
    }

    @Test
    void patch_shouldMoveAClauseToASparseOrdinal() {
        // Arrange -- 12.5 sits between the template's twelfth and thirteenth
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID rules = section(template, "Rules");
        UUID assignment = assignment("C013", template);
        PropertyDocumentCustomizationRow saved =
                customizationRepository.addClause(assignment, rules, SYSTEM_AGENT);

        // Act
        PropertyDocumentCustomizationRow moved = customizationRepository
                .patch(saved.uuid(), Map.of("ordinal", new BigDecimal("12.5"))).orElseThrow();

        // Assert
        assertEquals(0, new BigDecimal("12.5").compareTo(moved.ordinal()));
    }

    @Test
    void patch_shouldRefuseAColumnThatIsNotPatchable() {
        // Arrange -- what a row does and what it does it to are its identity
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID rules = section(template, "Rules");
        UUID assignment = assignment("C014", template);
        PropertyDocumentCustomizationRow saved =
                customizationRepository.addClause(assignment, rules, SYSTEM_AGENT);

        // Act + Assert
        assertThrows(InvalidDataAccessApiUsageException.class,
                () -> customizationRepository.patch(saved.uuid(), Map.of("action", "EXCLUDE_CLAUSE")));
    }

    // ---- reading and removing ------------------------------------------------

    @Test
    void findByAssignment_shouldReturnOnlyThisParksChanges() {
        // Arrange
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID septic = section(template, "Septic Addendum");
        UUID rules = section(template, "Rules");
        UUID mine = assignment("C015", template);
        UUID theirs = assignment("C016", template);

        PropertyDocumentCustomizationRow dropped =
                customizationRepository.excludeSection(mine, septic, SYSTEM_AGENT);
        PropertyDocumentCustomizationRow added =
                customizationRepository.addClause(mine, rules, SYSTEM_AGENT);
        customizationRepository.excludeSection(theirs, septic, SYSTEM_AGENT);

        // Act
        List<PropertyDocumentCustomizationRow> found = customizationRepository.findByAssignment(mine);

        // Assert
        assertEquals(Set.of(dropped.uuid(), added.uuid()), uuids(found));
    }

    @Test
    void findByAssignmentIds_shouldHydrateAWholeDocumentListInOneQuery() {
        // Arrange
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID septic = section(template, "Septic Addendum");
        UUID first = assignment("C017", template);
        UUID second = assignment("C018", template);

        customizationRepository.excludeSection(first, septic, SYSTEM_AGENT);
        customizationRepository.excludeSection(second, septic, SYSTEM_AGENT);

        // Act
        List<PropertyDocumentCustomizationRow> found =
                customizationRepository.findByAssignmentIds(List.of(first, second));

        // Assert
        assertEquals(2, found.size());
    }

    @Test
    void findByAssignmentIds_shouldReturnEmpty_forNoIds() {
        // Arrange -- a park with no assignments must not become "WHERE IN ()"
        // Act + Assert
        assertEquals(List.of(), customizationRepository.findByAssignmentIds(List.of()));
    }

    @Test
    void findReferencing_shouldNameEveryParkThatDroppedIt() {
        // Arrange -- the list behind a refusal to retire a section
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID septic = section(template, "Septic Addendum");
        UUID first = assignment("C019", template);
        UUID second = assignment("C020", template);

        customizationRepository.excludeSection(first, septic, SYSTEM_AGENT);
        customizationRepository.excludeSection(second, septic, SYSTEM_AGENT);

        // Act
        List<PropertyDocumentCustomizationRow> found = customizationRepository.findReferencing(septic);

        // Assert
        assertEquals(2, found.size());
    }

    @Test
    void softDelete_shouldPutTheDocumentBackTheWayTheTemplateWroteIt() {
        // Arrange
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID septic = section(template, "Septic Addendum");
        UUID assignment = assignment("C021", template);
        PropertyDocumentCustomizationRow saved =
                customizationRepository.excludeSection(assignment, septic, SYSTEM_AGENT);

        // Act
        boolean removed = customizationRepository.softDelete(saved.uuid());

        // Assert
        assertTrue(removed);
        assertEquals(Optional.empty(), customizationRepository.findById(saved.uuid()));
        assertEquals(List.of(), customizationRepository.findByAssignment(assignment));
        assertTrue(customizationRepository.findExclusion(assignment, septic).isEmpty());
    }

    @Test
    void softDelete_shouldReturnFalse_whenItWasAlreadyUndone() {
        // Arrange
        DocumentTemplateRow template = leaseTemplate("WA Land Lease");
        UUID septic = section(template, "Septic Addendum");
        UUID assignment = assignment("C022", template);
        PropertyDocumentCustomizationRow saved =
                customizationRepository.excludeSection(assignment, septic, SYSTEM_AGENT);
        customizationRepository.softDelete(saved.uuid());

        // Act + Assert
        assertFalse(customizationRepository.softDelete(saved.uuid()));
    }
}

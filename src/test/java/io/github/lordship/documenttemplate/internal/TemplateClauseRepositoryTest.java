package io.github.lordship.documenttemplate.internal;

import io.github.lordship.IntegrationTest;
import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
public class TemplateClauseRepositoryTest extends IntegrationTest {

    // The SYSTEM agent seeded in V10, which is what V11 attributes its rows to.
    static final UUID SYSTEM_AGENT = UUID.fromString("00000000-0000-7000-8000-000000000002");

    @Autowired
    DocumentTemplateRepository documentTemplateRepository;

    @Autowired
    DocumentSectionRepository documentSectionRepository;

    @Autowired
    TemplateClauseRepository templateClauseRepository;

    private UUID section(String templateName, String sectionName) {
        UUID templateId = documentTemplateRepository
                .save(templateName, AgreementType.LAND, InstrumentType.LEASE, SYSTEM_AGENT)
                .uuid();
        return documentSectionRepository.save(templateId, sectionName, SYSTEM_AGENT).uuid();
    }

    private TemplateClauseRow clause(UUID sectionId) {
        return templateClauseRepository.save(sectionId, SYSTEM_AGENT);
    }

    private TemplateClauseRow clause(UUID sectionId, String key, String body) {
        TemplateClauseRow saved = clause(sectionId);
        return templateClauseRepository
                .patch(saved.uuid(), Map.of("clause_key", key, "body", body))
                .orElseThrow();
    }

    private Set<UUID> uuids(List<TemplateClauseRow> rows) {
        return rows.stream().map(TemplateClauseRow::uuid).collect(Collectors.toSet());
    }

    // ---- save ----------------------------------------------------------------

    @Test
    void save_shouldPersistAnEmptyClause_withOnlyItsSection() {
        // Arrange
        UUID sectionId = section("Clause defaults", "Lease");

        // Act: every column but the section is nullable, so "add clause" is a
        // button rather than a form
        TemplateClauseRow saved = clause(sectionId);

        // Assert
        assertNotNull(saved.uuid());
        assertEquals(sectionId, saved.section());
        assertNull(saved.clauseKey());
        assertNull(saved.title());
        assertNull(saved.body());
        assertNull(saved.conditionField());
        assertFalse(saved.required());
        assertNull(saved.statuteRef());
        assertNotNull(saved.createdAt());
        assertNull(saved.deletedAt());
    }

    // NULL in the column, empty list in Java -- so appliesTo and isConditional
    // never have to reason about a null collection.
    @Test
    void save_shouldMapANullConditionValues_toAnEmptyList() {
        // Arrange & Act
        TemplateClauseRow saved = clause(section("Null array", "Lease"));

        // Assert
        assertNotNull(saved.conditionValues());
        assertTrue(saved.conditionValues().isEmpty());
    }

    @Test
    void save_shouldFail_whenSectionDoesNotExist() {
        // Act & Assert: the section foreign key
        assertThrows(DataIntegrityViolationException.class,
                () -> clause(UUID.randomUUID()));
    }

    // ---- ordinal assignment --------------------------------------------------

    @Test
    void save_shouldAssignTheNextOrdinal_startingAtOne() {
        // Arrange
        UUID sectionId = section("Clause ordinals", "Lease");

        // Act
        TemplateClauseRow first = clause(sectionId);
        TemplateClauseRow second = clause(sectionId);

        // Assert
        assertEquals(0, BigDecimal.ONE.compareTo(first.ordinal()));
        assertEquals(0, BigDecimal.valueOf(2).compareTo(second.ordinal()));
    }

    // Clause ordinals are scoped to the SECTION, not the template. Two sections
    // of the same document each number their clauses from one.
    @Test
    void save_shouldScopeTheOrdinal_toItsOwnSection() {
        // Arrange
        UUID templateId = documentTemplateRepository
                .save("Two sections", AgreementType.LAND, InstrumentType.LEASE, SYSTEM_AGENT)
                .uuid();
        UUID lease = documentSectionRepository.save(templateId, "Lease", SYSTEM_AGENT).uuid();
        UUID rules = documentSectionRepository.save(templateId, "Rules", SYSTEM_AGENT).uuid();
        clause(lease);
        clause(lease);

        // Act
        TemplateClauseRow firstInRules = clause(rules);

        // Assert
        assertEquals(0, BigDecimal.ONE.compareTo(firstInRules.ordinal()));
    }

    @Test
    void save_shouldCountSoftDeletedSiblings_whenComputingTheOrdinal() {
        // Arrange
        UUID sectionId = section("Clause reuse", "Lease");
        clause(sectionId);
        TemplateClauseRow doomed = clause(sectionId);
        templateClauseRepository.softDelete(doomed.uuid());

        // Act
        TemplateClauseRow replacement = clause(sectionId);

        // Assert: past the deleted one, which keeps its number for a restore
        assertTrue(replacement.ordinal().compareTo(doomed.ordinal()) > 0);
    }

    // ---- condition_values ----------------------------------------------------

    // TEXT[] is the one column here the driver cannot guess: the ::text[] cast
    // in the patch SQL is what stops it being sent as a record.
    @Test
    void patch_shouldRoundTripConditionValues_asATextArray() {
        // Arrange
        TemplateClauseRow saved = clause(section("NSF doc", "Lease"));

        // Act
        Optional<TemplateClauseRow> patched = templateClauseRepository.patch(saved.uuid(), Map.of(
                "condition_field", "term.nsf_fee_method",
                "condition_values", List.of("FLAT", "BANK_OR_FLAT")));

        // Assert
        assertTrue(patched.isPresent());
        assertEquals("term.nsf_fee_method", patched.get().conditionField());
        assertEquals(List.of("FLAT", "BANK_OR_FLAT"), patched.get().conditionValues());

        // And it survives a round trip through the row mapper on the way back out
        assertEquals(List.of("FLAT", "BANK_OR_FLAT"),
                templateClauseRepository.findById(saved.uuid()).orElseThrow().conditionValues());
    }

    @Test
    void patch_shouldPreserveTheOrderOfConditionValues() {
        // Arrange
        TemplateClauseRow saved = clause(section("Ordered values", "Lease"));

        // Act
        Optional<TemplateClauseRow> patched = templateClauseRepository.patch(saved.uuid(),
                Map.of("condition_values", List.of("C", "A", "B")));

        // Assert
        assertTrue(patched.isPresent());
        assertEquals(List.of("C", "A", "B"), patched.get().conditionValues());
    }

    // Sending [] is how a clause is made unconditional again. It stores NULL
    // rather than {}, so "no condition" has exactly one representation.
    @Test
    void patch_shouldClearTheCondition_whenGivenAnEmptyList() {
        // Arrange
        TemplateClauseRow saved = clause(section("Clearing", "Lease"));
        templateClauseRepository.patch(saved.uuid(), Map.of("condition_values", List.of("FLAT")));

        // Act
        Optional<TemplateClauseRow> patched = templateClauseRepository.patch(
                saved.uuid(), Map.of("condition_values", List.of()));

        // Assert
        assertTrue(patched.isPresent());
        assertTrue(patched.get().conditionValues().isEmpty());
    }

    @Test
    void patch_shouldClearTheCondition_whenGivenNull() {
        // Arrange
        TemplateClauseRow saved = clause(section("Nulling", "Lease"));
        templateClauseRepository.patch(saved.uuid(), Map.of("condition_values", List.of("FLAT")));

        Map<String, Object> changes = new HashMap<>();
        changes.put("condition_field", null);
        changes.put("condition_values", null);

        // Act
        Optional<TemplateClauseRow> patched = templateClauseRepository.patch(saved.uuid(), changes);

        // Assert
        assertTrue(patched.isPresent());
        assertNull(patched.get().conditionField());
        assertTrue(patched.get().conditionValues().isEmpty());
    }

    @Test
    void patch_shouldThrow_whenConditionValuesIsNotAList() {
        // Arrange
        TemplateClauseRow saved = clause(section("Bad values", "Lease"));

        // Act & Assert
        // @Repository exception translation wraps the IllegalArgumentException.
        assertThrows(InvalidDataAccessApiUsageException.class, () ->
                templateClauseRepository.patch(saved.uuid(), Map.of("condition_values", "FLAT")));
    }

    // ---- findBySection -------------------------------------------------------

    @Test
    void findBySection_shouldOrderByOrdinal_notInsertionOrder() {
        // Arrange
        UUID sectionId = section("Clause ordering", "Lease");
        TemplateClauseRow first = clause(sectionId, "PRINTED_SECOND", "b");
        TemplateClauseRow second = clause(sectionId, "PRINTED_FIRST", "a");
        templateClauseRepository.patch(first.uuid(), Map.of("ordinal", new BigDecimal("900")));
        templateClauseRepository.patch(second.uuid(), Map.of("ordinal", new BigDecimal("100")));

        // Act
        List<String> keys = templateClauseRepository.findBySection(sectionId).stream()
                .map(TemplateClauseRow::clauseKey)
                .toList();

        // Assert
        assertEquals(List.of("PRINTED_FIRST", "PRINTED_SECOND"), keys);
    }

    @Test
    void findBySection_shouldExcludeSoftDeletedClauses() {
        // Arrange
        UUID sectionId = section("Clause deletions", "Lease");
        TemplateClauseRow kept = clause(sectionId);
        TemplateClauseRow removed = clause(sectionId);
        templateClauseRepository.softDelete(removed.uuid());

        // Act & Assert
        assertEquals(Set.of(kept.uuid()), uuids(templateClauseRepository.findBySection(sectionId)));
    }

    // ---- findBySectionIds ----------------------------------------------------

    // Hydrating a whole document is two queries, not one per section.
    @Test
    void findBySectionIds_shouldReturnEveryClause_acrossSeveralSections() {
        // Arrange
        UUID templateId = documentTemplateRepository
                .save("Bulk hydrate", AgreementType.LAND, InstrumentType.LEASE, SYSTEM_AGENT)
                .uuid();
        UUID lease = documentSectionRepository.save(templateId, "Lease", SYSTEM_AGENT).uuid();
        UUID rules = documentSectionRepository.save(templateId, "Rules", SYSTEM_AGENT).uuid();
        TemplateClauseRow a = clause(lease);
        TemplateClauseRow b = clause(rules);

        // Act
        List<TemplateClauseRow> found = templateClauseRepository.findBySectionIds(List.of(lease, rules));

        // Assert
        assertEquals(Set.of(a.uuid(), b.uuid()), uuids(found));
    }

    @Test
    void findBySectionIds_shouldGroupClausesBySection() {
        // Arrange
        UUID templateId = documentTemplateRepository
                .save("Grouped", AgreementType.LAND, InstrumentType.LEASE, SYSTEM_AGENT)
                .uuid();
        UUID lease = documentSectionRepository.save(templateId, "Lease", SYSTEM_AGENT).uuid();
        UUID rules = documentSectionRepository.save(templateId, "Rules", SYSTEM_AGENT).uuid();
        clause(lease);
        clause(rules);
        clause(lease);

        // Act: ORDER BY section, ordinal -- one section's clauses never
        // interleave with another's
        List<UUID> sections = templateClauseRepository.findBySectionIds(List.of(lease, rules)).stream()
                .map(TemplateClauseRow::section)
                .toList();

        // Assert
        assertEquals(3, sections.size());
        assertEquals(sections.get(0), sections.get(1));
        assertNotEquals(sections.get(1), sections.get(2));
    }

    // The empty case is short-circuited in Java: IN () is not valid SQL.
    @Test
    void findBySectionIds_shouldReturnEmpty_forAnEmptyCollection() {
        assertTrue(templateClauseRepository.findBySectionIds(List.of()).isEmpty());
    }

    @Test
    void findBySectionIds_shouldReturnEmpty_whenNoSectionIsKnown() {
        assertTrue(templateClauseRepository.findBySectionIds(List.of(UUID.randomUUID())).isEmpty());
    }

    // ---- findReferencingToken ------------------------------------------------

    // What breaks if a token is renamed or retired.
    @Test
    void findReferencingToken_shouldFindEveryClauseUsingIt() {
        // Arrange
        UUID sectionId = section("Token search", "Lease");
        TemplateClauseRow uses = clause(sectionId, "RENT", "Rent of {{term.rate}} is due monthly.");
        TemplateClauseRow doesNot = clause(sectionId, "QUIET", "Quiet hours begin at ten.");

        // Act
        Set<UUID> found = uuids(templateClauseRepository.findReferencingToken("term.rate"));

        // Assert
        assertTrue(found.contains(uses.uuid()));
        assertFalse(found.contains(doesNot.uuid()));
    }

    // The braces are part of the pattern, so a token name that is a prefix of
    // another does not drag it in.
    @Test
    void findReferencingToken_shouldNotMatchAPrefixOfALongerToken() {
        // Arrange
        UUID sectionId = section("Prefix", "Lease");
        TemplateClauseRow saved = clause(sectionId, "LATE", "A late fee of {{term.late_fee_amount}}.");

        // Act & Assert
        assertFalse(uuids(templateClauseRepository.findReferencingToken("term.late_fee"))
                .contains(saved.uuid()));
        assertTrue(uuids(templateClauseRepository.findReferencingToken("term.late_fee_amount"))
                .contains(saved.uuid()));
    }

    @Test
    void findReferencingToken_shouldExcludeSoftDeletedClauses() {
        // Arrange
        UUID sectionId = section("Token deletions", "Lease");
        TemplateClauseRow saved = clause(sectionId, "RENT", "Rent of {{term.rate}}.");
        templateClauseRepository.softDelete(saved.uuid());

        // Act & Assert
        assertFalse(uuids(templateClauseRepository.findReferencingToken("term.rate"))
                .contains(saved.uuid()));
    }

    @Test
    void findReferencingToken_shouldReturnEmpty_forATokenNobodyUses() {
        assertTrue(templateClauseRepository.findReferencingToken("term.no_such_token").isEmpty());
    }

    // ---- patch ---------------------------------------------------------------

    @Test
    void patch_shouldUpdateEveryWhitelistedColumn() {
        // Arrange
        TemplateClauseRow saved = clause(section("Patchable clause", "Lease"));

        Map<String, Object> changes = Map.of(
                "clause_key", "LATE_CHARGES",
                "title", "Late Charges",
                "body", "A late fee of {{term.late_fee_amount}} applies.",
                "condition_field", "term.late_fee_method",
                "condition_values", List.of("FLAT"),
                "required", true,
                "statute_ref", "RCW 59.20.070",
                "note", "Flat-fee parks only"
        );

        // Act
        Optional<TemplateClauseRow> patched = templateClauseRepository.patch(saved.uuid(), changes);

        // Assert
        assertTrue(patched.isPresent());
        TemplateClauseRow row = patched.get();
        assertEquals("LATE_CHARGES", row.clauseKey());
        assertEquals("Late Charges", row.title());
        assertEquals("A late fee of {{term.late_fee_amount}} applies.", row.body());
        assertEquals("term.late_fee_method", row.conditionField());
        assertEquals(List.of("FLAT"), row.conditionValues());
        assertTrue(row.required());
        assertEquals("RCW 59.20.070", row.statuteRef());
        assertEquals("Flat-fee parks only", row.note());
    }

    // A clause cannot be dragged into another section this way: its ordinal
    // means nothing in a section it was not numbered against.
    @Test
    void patch_shouldThrow_whenSectionIsRequested() {
        // Arrange
        TemplateClauseRow saved = clause(section("Fixed parent clause", "Lease"));

        // Act & Assert
        assertThrows(InvalidDataAccessApiUsageException.class, () ->
                templateClauseRepository.patch(saved.uuid(), Map.of("section", UUID.randomUUID())));
    }

    @Test
    void patch_shouldReturnUnchangedRow_withEmptyChanges() {
        // Arrange
        TemplateClauseRow saved = clause(section("Untouched clause", "Lease"), "KEEP", "unchanged");

        // Act
        Optional<TemplateClauseRow> patched = templateClauseRepository.patch(saved.uuid(), Map.of());

        // Assert
        assertTrue(patched.isPresent());
        assertEquals("unchanged", patched.get().body());
    }

    @Test
    void patch_shouldReturnEmpty_whenRowIsSoftDeleted() {
        // Arrange
        TemplateClauseRow saved = clause(section("Deleted clause", "Lease"));
        templateClauseRepository.softDelete(saved.uuid());

        // Act & Assert
        assertTrue(templateClauseRepository.patch(saved.uuid(), Map.of("body", "x")).isEmpty());
    }

    // ---- softDelete ----------------------------------------------------------

    @Test
    void softDelete_shouldReturnTrue_thenFalseOnASecondCall() {
        // Arrange
        TemplateClauseRow saved = clause(section("Twice clause", "Lease"));

        // Act & Assert
        assertTrue(templateClauseRepository.softDelete(saved.uuid()));
        assertFalse(templateClauseRepository.softDelete(saved.uuid()));
    }

    @Test
    void softDelete_shouldReturnFalse_whenUuidIsUnknown() {
        assertFalse(templateClauseRepository.softDelete(UUID.randomUUID()));
    }
}
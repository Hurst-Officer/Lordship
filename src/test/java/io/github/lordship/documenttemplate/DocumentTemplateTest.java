package io.github.lordship.documenttemplate;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.InstrumentType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The worklist and the preview, both pure. Between them they answer the two
 * questions an author asks -- what have I not written, and what would this
 * actually come out as -- so neither needs a database to be worth testing.
 */
public class DocumentTemplateTest {

    private TemplateClause clause(BigDecimal ordinal, String key, String body,
                                  String conditionField, List<String> conditionValues) {
        return new TemplateClause(
                UUID.randomUUID(), ordinal, key, null, body,
                conditionField, conditionValues, false, null, null, null, null, null, null, true, null, null);
    }

    private DocumentSection section(BigDecimal ordinal, String name, TemplateClause... clauses) {
        return new DocumentSection(
                UUID.randomUUID(), ordinal, name, name.toUpperCase(),
                false, false, false, null, null, null, null, List.of(clauses), null, null, null, null);
    }

    private DocumentTemplate template(DocumentSection... sections) {
        return new DocumentTemplate(
                UUID.randomUUID(), "Test Lease", AgreementType.LAND, InstrumentType.LEASE,
                1, null, null, null, List.of(sections), List.of());
    }

    // ---- ordering ------------------------------------------------------------

    // Ordinals are sparse on purpose, so 1..n is never a safe assumption.
    @Test
    void sectionsInOrder_shouldSortByOrdinal_notInsertionOrder() {
        // Arrange
        DocumentTemplate template = template(
                section(BigDecimal.valueOf(3000), "Third"),
                section(BigDecimal.valueOf(1000), "First"),
                section(BigDecimal.valueOf(1500), "Second"));

        // Act
        List<DocumentSection> ordered = template.sectionsInOrder();

        // Assert
        assertEquals("First", ordered.get(0).name());
        assertEquals("Second", ordered.get(1).name());
        assertEquals("Third", ordered.get(2).name());
    }

    @Test
    void clausesInOrder_shouldFlattenSections_inPrintOrder() {
        // Arrange
        DocumentTemplate template = template(
                section(BigDecimal.valueOf(2000), "Rules",
                        clause(BigDecimal.valueOf(10), "RULE_B", "b", null, List.of()),
                        clause(BigDecimal.valueOf(5), "RULE_A", "a", null, List.of())),
                section(BigDecimal.valueOf(1000), "Lease",
                        clause(BigDecimal.valueOf(1), "LEASE_A", "x", null, List.of())));

        // Act
        List<String> keys = template.clausesInOrder().stream().map(TemplateClause::clauseKey).toList();

        // Assert
        assertEquals(List.of("LEASE_A", "RULE_A", "RULE_B"), keys);
    }

    @Test
    void section_shouldBeFoundByKey_notUuid() {
        // Arrange: keys are stable across versions, uuids are not
        DocumentTemplate template = template(section(BigDecimal.ONE, "Septic"));

        // Act
        Optional<DocumentSection> found = template.section("SEPTIC");

        // Assert
        assertTrue(found.isPresent());
        assertTrue(template.section("NO_SUCH_SECTION").isEmpty());
    }

    // ---- conditionWorklist ---------------------------------------------------

    @Test
    void conditionWorklist_shouldListEveryBranchableMethod_evenUntouchedOnes() {
        // Arrange: nothing in this document branches on anything
        DocumentTemplate template = template(
                section(BigDecimal.ONE, "Lease",
                        clause(BigDecimal.ONE, "RENT", "Rent is {{term.rate}}.", null, List.of())));

        // Act
        List<DocumentTemplate.MethodCoverage> worklist = template.conditionWorklist();

        // Assert: the worklist is what tells an author what they have not started
        assertFalse(worklist.isEmpty());
        assertTrue(worklist.stream().allMatch(DocumentTemplate.MethodCoverage::untouched));
        assertTrue(worklist.stream().anyMatch(m -> "term.trash_method".equals(m.conditionField())));
    }

    @Test
    void conditionWorklist_shouldSeparateCoveredFromUncovered() {
        // Arrange
        DocumentTemplate template = template(
                section(BigDecimal.ONE, "Lease",
                        clause(BigDecimal.ONE, "NSF_BANK", "…{{term.nsf_fee_amount}}…",
                                "term.nsf_fee_method", List.of("BANK_OR_FLAT"))));

        // Act
        DocumentTemplate.MethodCoverage nsf = template.conditionWorklist().stream()
                .filter(m -> "term.nsf_fee_method".equals(m.conditionField()))
                .findFirst()
                .orElseThrow();

        // Assert
        assertEquals(List.of("BANK_OR_FLAT"), nsf.covered());
        assertEquals(List.of("FLAT", "NONE"), nsf.uncovered());
        assertEquals(1, nsf.clauseCount());
        assertFalse(nsf.untouched());
    }

    // ---- preview -------------------------------------------------------------

    @Test
    void preview_shouldPrintUnconditionalClauses_whateverTheMethodValues() {
        // Arrange
        DocumentTemplate template = template(
                section(BigDecimal.ONE, "Lease",
                        clause(BigDecimal.ONE, "RENT", "Rent is {{term.rate}}.", null, List.of())));

        // Act
        DocumentTemplate.Preview preview = template.preview(Map.of());

        // Assert
        assertEquals(1, preview.sections().size());
        assertEquals(1, preview.sections().get(0).clauses().size());
        assertTrue(preview.skipped().isEmpty());
    }

    @Test
    void preview_shouldPrintOnlyTheMatchingBranch() {
        // Arrange: the three-clause pattern the whole mechanism exists for
        DocumentTemplate template = template(
                section(BigDecimal.ONE, "Lease",
                        clause(BigDecimal.valueOf(10), "NSF_BANK", "…whichever is greater.",
                                "term.nsf_fee_method", List.of("BANK_OR_FLAT")),
                        clause(BigDecimal.valueOf(20), "NSF_FLAT", "…a fee of {{term.nsf_fee_amount}}.",
                                "term.nsf_fee_method", List.of("FLAT"))));

        // Act
        DocumentTemplate.Preview preview =
                template.preview(Map.of("term.nsf_fee_method", "FLAT"));

        // Assert
        assertEquals(1, preview.sections().size());
        assertEquals("NSF_FLAT", preview.sections().get(0).clauses().get(0).clauseKey());
        assertEquals(1, preview.skipped().size());
        assertEquals("NSF_BANK", preview.skipped().get(0).clauseKey());
    }

    @Test
    void preview_shouldPrintNothing_forAMethodValueNoClauseCovers() {
        // Arrange: NONE has no clause, which is the correct answer for a park
        // that charges no NSF fee
        DocumentTemplate template = template(
                section(BigDecimal.ONE, "Lease",
                        clause(BigDecimal.ONE, "NSF_FLAT", "…a fee…",
                                "term.nsf_fee_method", List.of("FLAT"))));

        // Act
        DocumentTemplate.Preview preview =
                template.preview(Map.of("term.nsf_fee_method", "NONE"));

        // Assert
        assertTrue(preview.sections().isEmpty());
        assertEquals(1, preview.skipped().size());
        assertTrue(preview.skipped().get(0).reason().contains("NONE"));
    }

    // A half-specified deal should show its holes rather than a document that
    // looks finished.
    @Test
    void preview_shouldSkipAndExplain_whenAMethodIsNotSupplied() {
        // Arrange
        DocumentTemplate template = template(
                section(BigDecimal.ONE, "Lease",
                        clause(BigDecimal.ONE, "NSF_FLAT", "…a fee…",
                                "term.nsf_fee_method", List.of("FLAT"))));

        // Act
        DocumentTemplate.Preview preview = template.preview(Map.of());

        // Assert
        assertTrue(preview.sections().isEmpty());
        assertEquals(1, preview.skipped().size());
        assertTrue(preview.skipped().get(0).reason().contains("was not supplied"));
    }

    // An empty heading in a lease is worse than no heading.
    @Test
    void preview_shouldDropASection_whenEveryClauseInItSkips() {
        // Arrange
        DocumentTemplate template = template(
                section(BigDecimal.valueOf(1000), "Lease",
                        clause(BigDecimal.ONE, "RENT", "Rent is {{term.rate}}.", null, List.of())),
                section(BigDecimal.valueOf(2000), "Septic",
                        clause(BigDecimal.ONE, "SEPTIC", "…septic…",
                                "term.sewer_method", List.of("SUBMETERED"))));

        // Act
        DocumentTemplate.Preview preview =
                template.preview(Map.of("term.sewer_method", "RUBS"));

        // Assert
        assertEquals(1, preview.sections().size());
        assertEquals("Lease", preview.sections().get(0).name());
    }

    @Test
    void preview_shouldKeepTokensInTheBody() {
        // Arrange: preview answers whether the document reads right, not what
        // the figures are -- substitution belongs to generate
        DocumentTemplate template = template(
                section(BigDecimal.ONE, "Lease",
                        clause(BigDecimal.ONE, "RENT", "Rent is {{term.rate}}.", null, List.of())));

        // Act
        DocumentTemplate.Preview preview = template.preview(Map.of());

        // Assert
        assertEquals("Rent is {{term.rate}}.", preview.sections().get(0).clauses().get(0).body());
    }
}
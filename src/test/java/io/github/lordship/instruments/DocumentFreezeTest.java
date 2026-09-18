package io.github.lordship.instruments;

import io.github.lordship.documenttemplate.CustomizationAction;
import io.github.lordship.documenttemplate.DocumentSection;
import io.github.lordship.documenttemplate.PropertyDocumentCustomization;
import io.github.lordship.documenttemplate.TemplateClause;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class DocumentFreezeTest {

    // ---- clause selection ----------------------------------------------------

    @Test
    void freeze_shouldKeepAnUnconditionalClause() {
        // Arrange
        DocumentSection rent = section("Rent", false, false,
                clause("1", "rent", "Tenant shall pay {{term.rate}}.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rent), TokenValues.of(Map.of("term.rate", "$4,200.00")));

        // Assert
        assertEquals(1, out.sections().size());
        assertEquals("Tenant shall pay $4,200.00.", out.sections().get(0).clauses().get(0).body());
        assertTrue(out.isComplete());
    }

    @Test
    void freeze_shouldDropAClauseWrittenForADifferentMethod() {
        // Arrange -- two water clauses, one per method; this park is on RUBS
        DocumentSection water = section("Water", false, false,
                clause("1", "water_flat", "Water is {{term.water_flat_amount}} per month.",
                        "term.water_method", List.of("FLAT")),
                clause("2", "water_rubs", "Water is billed by allocation.",
                        "term.water_method", List.of("RUBS")));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(water), TokenValues.of(Map.of("term.water_method", "RUBS")));

        // Assert -- the flat clause is dropped outright rather than printed with
        // a blank where its amount would be
        List<DocumentFreeze.FrozenClause> kept = out.sections().get(0).clauses();
        assertEquals(1, kept.size());
        assertEquals("water_rubs", kept.get(0).clauseKey());
        assertTrue(out.isComplete());
    }

    @Test
    void freeze_shouldDropAConditionalClause_whenTheDealNeverSuppliedThatMethod() {
        // Arrange -- a half-specified deal should show holes, not a plausible document
        DocumentSection water = section("Water", false, false,
                clause("1", "water_flat", "Water is {{term.water_flat_amount}}.",
                        "term.water_method", List.of("FLAT")));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(List.of(water), TokenValues.of(Map.of()));

        // Assert
        assertTrue(out.sections().isEmpty());
    }

    // ---- sections ------------------------------------------------------------

    @Test
    void freeze_shouldDropASectionWhoseClausesAllDropped() {
        // Arrange -- a community on city sewer
        DocumentSection septic = section("Septic Addendum", false, false,
                clause("1", "septic", "The tank is pumped annually.",
                        "term.sewer_method", List.of("SUBMETERED")));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(septic), TokenValues.of(Map.of("term.sewer_method", "RUBS")));

        // Assert -- an empty heading reading SEPTIC ADDENDUM is worse than no section
        assertTrue(out.sections().isEmpty());
        assertTrue(out.isComplete());
    }

    @Test
    void freeze_shouldKeepAnEmptySignatureBlock() {
        // Arrange -- a signature block has no clauses by nature
        DocumentSection signatures = section("Signatures", true, false);

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(List.of(signatures), TokenValues.of(Map.of()));

        // Assert
        assertEquals(1, out.sections().size());
        assertTrue(out.sections().get(0).signatureBlock());
    }

    @Test
    void freeze_shouldReportARequiredSignatureBlock_thatEndedUpEmpty() {
        // Arrange -- being a signature block used to exempt a section from this report
        DocumentSection septic = section("Septic Addendum", true, true,
                clause("1", "septic", "Septic rules.", "term.sewer_method", List.of("FLAT")));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(septic), TokenValues.of(Map.of("term.sewer_method", "NONE")));

        // Assert
        assertEquals(List.of("Septic Addendum"), out.omittedRequired());
        assertFalse(out.isComplete());
    }

    @Test
    void freeze_shouldReportARequiredSectionThatEndedUpEmpty() {
        // Arrange -- a mandated disclosure whose only clause did not apply
        DocumentSection disclosure = section("Rent History Disclosure", false, true,
                "RCW 59.20.045",
                clause("1", "history", "Rents were {{lot.rent_history_rate_1}}.",
                        "term.agreement_type", List.of("LAND")));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(disclosure), TokenValues.of(Map.of("term.agreement_type", "COMMERCIAL")));

        // Assert -- a missing mandated disclosure is a legal problem, not a
        // formatting one, so it is named rather than silently dropped
        assertTrue(out.sections().isEmpty());
        assertFalse(out.isComplete());
        assertEquals(List.of("Rent History Disclosure (RCW 59.20.045)"), out.omittedRequired());
    }

    @Test
    void freeze_shouldPrintSectionsAndClausesInOrdinalOrder() {
        // Arrange -- ordinals are sparse and out of order in the list
        DocumentSection second = section("Rules", false, false,
                clause("20", "b", "B.", null, List.of()),
                clause("10", "a", "A.", null, List.of()));
        DocumentSection first = section("Rent", false, false,
                clause("5", "rent", "Rent.", null, List.of()));
        first = withOrdinal(first, "1");
        second = withOrdinal(second, "2");

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(second, first), TokenValues.of(Map.of()));

        // Assert
        assertEquals(List.of("Rent", "Rules"),
                out.sections().stream().map(DocumentFreeze.FrozenSection::name).toList());
        assertEquals(List.of("a", "b"),
                out.sections().get(1).clauses().stream()
                        .map(DocumentFreeze.FrozenClause::clauseKey).toList());
    }

    // ---- the freeze itself ---------------------------------------------------

    @Test
    void freeze_shouldKeepBothTheWordsAndTheTokens() {
        // Arrange
        DocumentSection rent = section("Rent", false, false,
                clause("1", "rent", "Rent is {{term.rate}}.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rent), TokenValues.of(Map.of("term.rate", "$4,200.00")));

        // Assert -- re-resolving bodyTemplate later and comparing to body is what
        // makes "did the paper drift from the deal" a mechanical question
        DocumentFreeze.FrozenClause frozen = out.sections().get(0).clauses().get(0);
        assertEquals("Rent is $4,200.00.", frozen.body());
        assertEquals("Rent is {{term.rate}}.", frozen.bodyTemplate());
    }

    @Test
    void freeze_shouldRecordWhichClauseItCameFrom() {
        // Arrange
        TemplateClause source = clause("1", "rent", "Rent.", null, List.of());
        DocumentSection rent = section("Rent", false, false, source);

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(List.of(rent), TokenValues.of(Map.of()));

        // Assert -- deliberately not a foreign key: the clause may be retired later
        assertEquals(source.uuid(), out.sections().get(0).clauses().get(0).sourceClause());
    }

    @Test
    void freeze_shouldGatherUnresolvedTokensAcrossEveryClause() {
        // Arrange
        DocumentSection rent = section("Rent", false, false,
                clause("1", "rent", "Rent is {{term.rate}}.", null, List.of()),
                clause("2", "late", "Late after the {{term.late_after_day}}.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(List.of(rent), TokenValues.of(Map.of()));

        // Assert
        assertFalse(out.isComplete());
        assertEquals(List.of("term.rate", "term.late_after_day"), out.unresolved());
    }

    @Test
    void freeze_shouldSkipASoftDeletedClause() {
        // Arrange
        DocumentSection rent = section("Rent", false, false,
                clause("1", "rent", "Rent.", null, List.of()),
                softDeleted(clause("2", "retired", "Old wording.", null, List.of())));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(List.of(rent), TokenValues.of(Map.of()));

        // Assert
        assertEquals(1, out.sections().get(0).clauses().size());
        assertEquals("rent", out.sections().get(0).clauses().get(0).clauseKey());
    }

    // ---- numbering -----------------------------------------------------------

    @Test
    void freeze_shouldNumberFromOne_whateverTheTemplateOrdinalsAre() {
        // Arrange -- template ordinals are sparse so clauses can be inserted between
        DocumentSection rent = section("Rent", false, false,
                clause("10", "a", "A.", null, List.of()),
                clause("20", "b", "B.", null, List.of()),
                clause("50", "c", "C.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(List.of(rent), TokenValues.of(Map.of()));

        // Assert -- the author never types a number; it comes from print position
        assertEquals(List.of(1, 2, 3), numbersOf(out, 0));
    }

    @Test
    void freeze_shouldCloseTheGap_whenAConditionalClauseDidNotPrint() {
        // Arrange -- the middle clause is for a deal this tenant does not have
        DocumentSection rent = section("Rent", false, false,
                clause("10", "a", "A.", null, List.of()),
                clause("20", "flat_only", "B.", "term.water_method", List.of("FLAT")),
                clause("30", "c", "C.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rent), TokenValues.of(Map.of("term.water_method", "RUBS")));

        // Assert -- numbered 1, 2. A lease that reads 1, 3 looks like a mistake,
        // and whether a clause is the second depends on this tenant's deal
        assertEquals(List.of("a", "c"), keysOf(out, 0));
        assertEquals(List.of(1, 2), numbersOf(out, 0));
    }

    @Test
    void freeze_shouldRestartNumberingInEachSection() {
        // Arrange -- a section is a document in its own right
        DocumentSection rent = withOrdinal(section("Rent", false, false,
                clause("10", "rent_a", "A.", null, List.of()),
                clause("20", "rent_b", "B.", null, List.of())), "1");
        DocumentSection pets = withOrdinal(section("Pet Agreement", false, false,
                clause("10", "pet_a", "A.", null, List.of())), "2");

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rent, pets), TokenValues.of(Map.of()));

        // Assert -- the Pet Agreement does not continue the lease's numbering
        assertEquals(List.of(1, 2), numbersOf(out, 0));
        assertEquals(List.of(1), numbersOf(out, 1));
        assertEquals(List.of(1, 2),
                out.sections().stream().map(s -> s.ordinal().intValue()).toList());
    }

    @Test
    void freeze_shouldLeaveTheBodyWithoutANumber() {
        // Arrange -- the number is structural, not part of the words, so that
        // re-resolving bodyTemplate and comparing to body stays mechanical
        DocumentSection rent = section("Rent", false, false,
                clause("10", "rent", "The monthly rental shall be {{term.rate}}.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rent), TokenValues.of(Map.of("term.rate", "$4,200.00")));

        // Assert
        DocumentFreeze.FrozenClause frozen = out.sections().get(0).clauses().get(0);
        assertEquals("The monthly rental shall be $4,200.00.", frozen.body());
        assertEquals(1, frozen.ordinal().intValue());
    }

    // ---- what the park changed -----------------------------------------------

    @Test
    void freeze_shouldDropASectionThePropertyExcluded() {
        // Arrange -- this park is on city sewer, so the septic addendum does not apply
        DocumentSection septic = section("Septic Addendum", false, false,
                clause("1", "septic", "The tank is pumped annually.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(septic),
                List.of(excludeSection(septic)),
                List.of(),
                TokenValues.of(Map.of()));

        // Assert
        assertTrue(out.sections().isEmpty());
        assertTrue(out.isComplete());
    }

    @Test
    void freeze_shouldReportTheSection_whenAPropertyExcludedOneThatIsRequired() {
        // Arrange -- the save path refuses this, so a row that got in anyway is
        // a park quietly dropping a mandated disclosure
        DocumentSection disclosure = section("Rent History Disclosure", false, true,
                "RCW 59.20.045",
                clause("1", "history", "Rents were as follows.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(disclosure),
                List.of(excludeSection(disclosure)),
                List.of(),
                TokenValues.of(Map.of()));

        // Assert -- generation refuses by name rather than printing a lease without it
        assertTrue(out.sections().isEmpty());
        assertFalse(out.isComplete());
        assertEquals(List.of("Rent History Disclosure (RCW 59.20.045)"), out.omittedRequired());
    }

    @Test
    void freeze_shouldDropAClauseThePropertyExcluded() {
        // Arrange
        TemplateClause dropped = clause("20", "quiet_hours", "Quiet hours begin at 9pm.", null, List.of());
        DocumentSection rules = section("Rules", false, false,
                clause("10", "parking", "Park in your own space.", null, List.of()),
                dropped);

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rules),
                List.of(excludeClause(dropped)),
                List.of(),
                TokenValues.of(Map.of()));

        // Assert
        assertEquals(List.of("parking"), keysOf(out, 0));
    }

    @Test
    void freeze_shouldPrintAPropertyAddedClause_whereItsOrdinalPutsIt() {
        // Arrange -- a park rule of its own, authored to land between two template clauses
        DocumentSection rules = section("Rules", false, false,
                clause("10", "parking", "A.", null, List.of()),
                clause("30", "noise", "C.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rules),
                List.of(addClause(rules, "20", "No boats on the lot.", null, List.of())),
                List.of(),
                TokenValues.of(Map.of()));

        // Assert -- all three share one ordinal space, which is what lets a park
        // place its rule rather than append it
        List<DocumentFreeze.FrozenClause> kept = out.sections().get(0).clauses();
        assertEquals(3, kept.size());
        assertEquals("No boats on the lot.", kept.get(1).body());
        assertEquals(List.of(1, 2, 3), numbersOf(out, 0));
    }

    @Test
    void freeze_shouldRecordAPropertyAddedClauseAsPropertyOrigin() {
        // Arrange
        DocumentSection rules = section("Rules", false, false);
        PropertyDocumentCustomization added =
                addClause(rules, "10", "No boats on the lot.", null, List.of());

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rules), List.of(added), List.of(), TokenValues.of(Map.of()));

        // Assert -- origin is what says which table sourceClause points into
        DocumentFreeze.FrozenClause frozen = out.sections().get(0).clauses().get(0);
        assertEquals(ClauseOrigin.PROPERTY, frozen.origin());
        assertEquals(added.uuid(), frozen.sourceClause());
    }

    @Test
    void freeze_shouldDropAPropertyAddedClause_whenTheDealIsNotWhatItWasWrittenFor() {
        // Arrange -- a park may condition its own clause the same way a template can
        DocumentSection rules = section("Rules", false, false,
                clause("10", "parking", "A.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rules),
                List.of(addClause(rules, "20", "Water is billed flat.",
                        "term.water_method", List.of("FLAT"))),
                List.of(),
                TokenValues.of(Map.of("term.water_method", "RUBS")));

        // Assert
        assertEquals(1, out.sections().get(0).clauses().size());
    }

    @Test
    void freeze_shouldIgnoreASoftDeletedCustomization() {
        // Arrange -- the park undid its exclusion
        DocumentSection septic = section("Septic Addendum", false, false,
                clause("1", "septic", "The tank is pumped annually.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(septic),
                List.of(softDeleted(excludeSection(septic))),
                List.of(),
                TokenValues.of(Map.of()));

        // Assert
        assertEquals(1, out.sections().size());
    }

    // ---- what the office worker typed ----------------------------------------

    @Test
    void freeze_shouldPrintAClauseTypedOntoThisOneAgreement() {
        // Arrange -- the seller left a shed the tenant may keep until June. True
        // of this lease and no other one, ever
        DocumentSection rules = section("Rules", false, false,
                clause("10", "parking", "Park in your own space.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rules),
                List.of(),
                List.of(typedClause(rules, "20", "Tenant may keep the existing shed until June 1.")),
                TokenValues.of(Map.of()));

        // Assert
        assertEquals(2, out.sections().get(0).clauses().size());
        assertEquals("Tenant may keep the existing shed until June 1.",
                out.sections().get(0).clauses().get(1).body());
    }

    @Test
    void freeze_shouldRecordATypedClauseAsInstrumentOrigin() {
        // Arrange
        DocumentSection rules = section("Rules", false, false);
        InstrumentAddition typed = typedClause(rules, "10", "Tenant may keep the shed.");

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rules), List.of(), List.of(typed), TokenValues.of(Map.of()));

        // Assert -- the paragraph nobody vetted, findable as such on every lease
        // this park has out in the field
        DocumentFreeze.FrozenClause frozen = out.sections().get(0).clauses().get(0);
        assertEquals(ClauseOrigin.INSTRUMENT, frozen.origin());
        assertEquals(typed.uuid(), frozen.sourceClause());
    }

    @Test
    void freeze_shouldResolveTokensInATypedClause() {
        // Arrange -- an assistant writing about the rent should get the real figure
        DocumentSection rules = section("Rules", false, false);

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rules),
                List.of(),
                List.of(typedClause(rules, "10", "First month is abated from {{term.rate}}.")),
                TokenValues.of(Map.of("term.rate", "$4,200.00")));

        // Assert
        assertEquals("First month is abated from $4,200.00.",
                out.sections().get(0).clauses().get(0).body());
        assertTrue(out.isComplete());
    }

    @Test
    void freeze_shouldReportAnUnresolvedTokenInATypedClause() {
        // Arrange -- nothing typed by hand gets to skip the completeness check
        DocumentSection rules = section("Rules", false, false);

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rules),
                List.of(),
                List.of(typedClause(rules, "10", "Abated from {{term.rate}}.")),
                TokenValues.of(Map.of()));

        // Assert
        assertFalse(out.isComplete());
        assertEquals(List.of("term.rate"), out.unresolved());
    }

    @Test
    void freeze_shouldIgnoreASoftDeletedAddition() {
        // Arrange -- the assistant deleted the sentence before generating
        DocumentSection rules = section("Rules", false, false,
                clause("10", "parking", "A.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rules),
                List.of(),
                List.of(softDeleted(typedClause(rules, "20", "Tenant may keep the shed."))),
                TokenValues.of(Map.of()));

        // Assert
        assertEquals(1, out.sections().get(0).clauses().size());
    }

    @Test
    void freeze_shouldNumberEveryClauseTogether_whateverWroteIt() {
        // Arrange -- one section holding all three sources at once
        DocumentSection rules = section("Rules", false, false,
                clause("10", "parking", "A.", null, List.of()),
                clause("40", "noise", "D.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rules),
                List.of(addClause(rules, "20", "B.", null, List.of())),
                List.of(typedClause(rules, "30", "C.")),
                TokenValues.of(Map.of()));

        // Assert -- the tenant reads one numbered list, not three
        assertEquals(List.of("A.", "B.", "C.", "D."),
                out.sections().get(0).clauses().stream()
                        .map(DocumentFreeze.FrozenClause::body).toList());
        assertEquals(List.of(1, 2, 3, 4), numbersOf(out, 0));
        assertEquals(
                List.of(ClauseOrigin.TEMPLATE, ClauseOrigin.PROPERTY,
                        ClauseOrigin.INSTRUMENT, ClauseOrigin.TEMPLATE),
                out.sections().get(0).clauses().stream()
                        .map(DocumentFreeze.FrozenClause::origin).toList());
    }


    // ---- which clause wanted it ----------------------------------------------

    @Test
    void freeze_shouldRecordWhichClauseCouldNotBeFilled() {
        // Arrange -- two clauses, one of them short a value
        DocumentSection rent = section("Rent", false, false,
                clause("10", "rent", "Rent is {{term.rate}}.", null, List.of()),
                clause("20", "late", "Late after the {{term.late_after_day}}.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rent), TokenValues.of(Map.of("term.rate", "$4,200.00")));

        // Assert -- the hole belongs to the clause that asked, not to the document
        List<DocumentFreeze.FrozenClause> clauses = out.sections().get(0).clauses();
        assertEquals(List.of(), clauses.get(0).unresolved());
        assertTrue(clauses.get(0).isComplete());
        assertEquals(List.of("term.late_after_day"), clauses.get(1).unresolved());
        assertFalse(clauses.get(1).isComplete());
    }

    @Test
    void freeze_shouldNameTheSameTokenOnEveryClauseThatWantedIt() {
        // Arrange -- one missing value, wanted twice
        DocumentSection rent = section("Rent", false, false,
                clause("10", "a", "Pay {{term.rate}}.", null, List.of()),
                clause("20", "b", "The rate of {{term.rate}} is due monthly.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(List.of(rent), TokenValues.of(Map.of()));

        // Assert -- the document-wide list says it once, so a banner does not
        // read "2 values missing" when one is
        assertEquals(List.of("term.rate"), out.unresolved());
        assertEquals(List.of("term.rate"), out.sections().get(0).clauses().get(0).unresolved());
        assertEquals(List.of("term.rate"), out.sections().get(0).clauses().get(1).unresolved());
    }

    @Test
    void freeze_shouldRollUpEveryClausesHolesIntoTheDocumentList() {
        // Arrange -- holes in two different sections
        DocumentSection rent = withOrdinal(section("Rent", false, false,
                clause("10", "rent", "Rent is {{term.rate}}.", null, List.of())), "1");
        DocumentSection pets = withOrdinal(section("Pets", false, false,
                clause("10", "pets", "Pet fee is {{term.pet_fee}}.", null, List.of())), "2");

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rent, pets), TokenValues.of(Map.of()));

        // Assert -- the rollup is what decides whether generation may proceed
        assertEquals(List.of("term.rate", "term.pet_fee"), out.unresolved());
        assertFalse(out.isComplete());
    }


    // ---- nesting and numbers -------------------------------------------------

    @Test
    void freeze_shouldLetterClausesUnderTheirParent() {
        // Arrange -- "2. Water:" with A, B, C under it
        TemplateClause water = clause("20", "water", "Water:", null, List.of());
        DocumentSection rules = section("Rules", false, false,
                clause("10", "rent", "Rent.", null, List.of()),
                water,
                under(water, clause("21", "check_valve", "Check valve.", null, List.of())),
                under(water, clause("22", "faucets", "Faucets.", null, List.of())),
                under(water, clause("23", "waste", "Wasteful water.", null, List.of())));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(List.of(rules), TokenValues.of(Map.of()));

        // Assert
        assertEquals(Arrays.asList("1.", "2.", "A.", "B.", "C."), shownOf(out, 0));
        assertEquals(Arrays.asList("1", "2", "2A", "2B", "2C"), labelsOf(out, 0));
        assertEquals(List.of(1, 1, 2, 2, 2), depthsOf(out, 0));
        assertTrue(out.isComplete());
    }

    @Test
    void freeze_shouldReletterTheRest_whenAChildDoesNotApply() {
        // Arrange -- A only prints for flat water; this park is on RUBS
        TemplateClause water = clause("10", "water", "Water:", null, List.of());
        DocumentSection rules = section("Rules", false, false,
                water,
                under(water, clause("11", "flat", "Flat.", "term.water_method", List.of("FLAT"))),
                under(water, clause("12", "faucets", "Faucets.", null, List.of())));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rules), TokenValues.of(Map.of("term.water_method", "RUBS")));

        // Assert
        assertEquals(Arrays.asList("1", "1A"), labelsOf(out, 0));
        assertEquals(List.of("water", "faucets"), keysOf(out, 0));
    }

    @Test
    void freeze_shouldDropTheChildren_whenTheirParentDoesNotApply() {
        // Arrange
        TemplateClause septic = clause("10", "septic", "Septic:", "term.sewer_method", List.of("FLAT"));
        DocumentSection rules = section("Rules", false, false,
                septic,
                under(septic, clause("11", "grease", "No grease.", null, List.of())),
                clause("20", "noise", "Noise.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rules), TokenValues.of(Map.of("term.sewer_method", "NONE")));

        // Assert -- the grease rule goes with its heading, and noise becomes 1
        assertEquals(List.of("noise"), keysOf(out, 0));
        assertEquals(List.of("1"), labelsOf(out, 0));
    }

    @Test
    void freeze_shouldDropTheChildren_whenAParkExcludesTheirParent() {
        // Arrange
        TemplateClause septic = clause("10", "septic", "Septic:", null, List.of());
        DocumentSection rules = section("Rules", false, false,
                septic,
                under(septic, clause("11", "grease", "No grease.", null, List.of())),
                clause("20", "noise", "Noise.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rules), List.of(excludeClause(septic)), List.of(), TokenValues.of(Map.of()));

        // Assert
        assertEquals(List.of("noise"), keysOf(out, 0));
    }

    @Test
    void freeze_shouldNotNumberAnUnnumberedClause() {
        // Arrange -- an opening paragraph, then the clauses proper
        DocumentSection lease = section("Lease", false, false,
                unnumbered(clause("5", "intro", "This agreement is made between...", null, List.of())),
                clause("10", "premises", "Premises.", null, List.of()),
                clause("20", "term", "Term.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(List.of(lease), TokenValues.of(Map.of()));

        // Assert -- the intro takes no number and does not use one up
        assertEquals(Arrays.asList(null, "1.", "2."), shownOf(out, 0));
        assertEquals(Arrays.asList(null, "1", "2"), labelsOf(out, 0));
        assertEquals(List.of(1, 2, 3), numbersOf(out, 0));
    }

    @Test
    void freeze_shouldUseTheSectionsOwnFormats() {
        // Arrange -- the admin wants II. and (a), cited as II(a)
        TemplateClause pets = clause("20", "pets", "Pets:", null, List.of());
        DocumentSection rules = withFormats(section("Rules", false, false,
                        clause("10", "rent", "Rent.", null, List.of()),
                        pets,
                        under(pets, clause("21", "weight", "Under 40 lbs.", null, List.of()))),
                List.of("{1:I}.", "({2:a})"), List.of("{1:I}", "{1:I}({2:a})"));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(List.of(rules), TokenValues.of(Map.of()));

        // Assert
        assertEquals(Arrays.asList("I.", "II.", "(a)"), shownOf(out, 0));
        assertEquals(Arrays.asList("I", "II", "II(a)"), labelsOf(out, 0));
    }

    @Test
    void freeze_shouldLetterAParksRuleAmongTheTemplatesOwn() {
        // Arrange -- the park adds a rule under Water, between B and C
        TemplateClause water = clause("10", "water", "Water:", null, List.of());
        DocumentSection rules = section("Rules", false, false,
                water,
                under(water, clause("11", "valve", "Valve.", null, List.of())),
                under(water, clause("12", "faucets", "Faucets.", null, List.of())),
                under(water, clause("13", "waste", "Waste.", null, List.of())));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(rules),
                List.of(addClauseUnder(water, rules, "12.5", "No hoses left running.")),
                List.of(),
                TokenValues.of(Map.of()));

        // Assert
        assertEquals(Arrays.asList("1", "1A", "1B", "1C", "1D"), labelsOf(out, 0));
        assertEquals("No hoses left running.", out.sections().get(0).clauses().get(3).body());
    }

    @Test
    void freeze_shouldLetterItemsUnderAnUnnumberedHeading_withoutUsingANumber() {
        // Arrange -- rule 3, then "Water:" with no number, then rule 4
        TemplateClause water = unnumbered(clause("20", "water", "Water:", null, List.of()));
        DocumentSection rules = section("Rules", false, false,
                clause("10", "utilities", "Utilities.", null, List.of()),
                water,
                under(water, clause("21", "valve", "Valve.", null, List.of())),
                under(water, clause("22", "faucets", "Faucets.", null, List.of())),
                clause("30", "homes", "Homes.", null, List.of()));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(List.of(rules), TokenValues.of(Map.of()));

        // Assert
        assertEquals(Arrays.asList("1.", null, "A.", "B.", "2."), shownOf(out, 0));
        assertEquals(Arrays.asList("1", null, "A", "B", "2"), labelsOf(out, 0));
    }

    @Test
    void freeze_shouldListTheAttachedAddenda_afterAParkDropsOne() {
        // Arrange -- the lease names its addenda; this park is on city sewer
        DocumentSection lease = withOrdinal(section("Lease", false, true,
                clause("10", "addendums", "Attached: {{packet.addenda}}.", null, List.of())), "1");
        DocumentSection pets = listedAddendum(withOrdinal(section("Pet Agreement", false, false,
                clause("10", "pets", "Pets.", null, List.of())), "2"));
        DocumentSection septic = listedAddendum(withOrdinal(section("Septic Addendum", false, false,
                clause("10", "septic", "Septic.", null, List.of())), "3"));

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(List.of(lease, pets, septic),
                List.of(excludeSection(septic)), List.of(), TokenValues.of(Map.of()));

        // Assert
        assertEquals("Attached: Pet Agreement.", out.sections().get(0).clauses().get(0).body());
        assertTrue(out.isComplete());
    }

    // ---- references ----------------------------------------------------------

    @Test
    void freeze_shouldPrintTheCitedClausesNumber() {
        // Arrange
        TemplateClause water = clause("20", "water", "Water:", null, List.of());
        TemplateClause faucets = under(water, clause("22", "faucets", "Faucets.", null, List.of()));
        DocumentSection rules = section("Rules", false, false,
                clause("10", "rent", "See Section " + ref(faucets) + ".", null, List.of()),
                water,
                under(water, clause("21", "valve", "Valve.", null, List.of())),
                faucets);

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(List.of(rules), TokenValues.of(Map.of()));

        // Assert -- the page says 2B, the stored template still says which clause
        DocumentFreeze.FrozenClause rent = out.sections().get(0).clauses().get(0);
        assertEquals("See Section 2B.", rent.body());
        assertEquals("See Section " + ref(faucets) + ".", rent.bodyTemplate());
        assertTrue(out.isComplete());
    }

    @Test
    void freeze_shouldFollowTheCitedClause_whenAClauseAboveItDrops() {
        // Arrange -- a conditional clause above the cited one
        TemplateClause pets = clause("30", "pets", "Pets.", null, List.of());
        DocumentSection lease = section("Lease", false, false,
                clause("10", "rent", "As in Section " + ref(pets) + ".", null, List.of()),
                clause("20", "septic", "Septic.", "term.sewer_method", List.of("FLAT")),
                pets);

        // Act
        DocumentFreeze.Frozen flat = DocumentFreeze.freeze(
                List.of(lease), TokenValues.of(Map.of("term.sewer_method", "FLAT")));
        DocumentFreeze.Frozen none = DocumentFreeze.freeze(
                List.of(lease), TokenValues.of(Map.of("term.sewer_method", "NONE")));

        // Assert
        assertEquals("As in Section 3.", flat.sections().get(0).clauses().get(0).body());
        assertEquals("As in Section 2.", none.sections().get(0).clauses().get(0).body());
    }

    @Test
    void freeze_shouldResolveAReferenceThroughWhicheverVariantPrinted() {
        // Arrange -- the author cited the BANK_OR_FLAT clause; this deal is FLAT
        TemplateClause bankOrFlat = clause("20", "nsf_bank", "Bank or flat.",
                "term.nsf_fee_method", List.of("BANK_OR_FLAT"));
        TemplateClause flatOnly = variantOf(bankOrFlat, clause("21", "nsf_flat", "Flat.",
                "term.nsf_fee_method", List.of("FLAT")));
        DocumentSection lease = section("Lease", false, false,
                clause("10", "rent", "Fees are in Section " + ref(bankOrFlat) + ".", null, List.of()),
                bankOrFlat,
                flatOnly);

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(lease), TokenValues.of(Map.of("term.nsf_fee_method", "FLAT")));

        // Assert
        assertEquals("Fees are in Section 2.", out.sections().get(0).clauses().get(0).body());
        assertTrue(out.isComplete());
    }

    @Test
    void freeze_shouldReportAReference_whoseClauseDidNotPrint() {
        // Arrange -- the cited septic clause does not apply to this deal
        TemplateClause septic = clause("20", "septic", "Septic.", "term.sewer_method", List.of("FLAT"));
        DocumentSection lease = section("Lease", false, false,
                clause("10", "rent", "See " + ref(septic) + ".", null, List.of()),
                septic);

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(lease), TokenValues.of(Map.of("term.sewer_method", "NONE")));

        // Assert -- left standing like a token, named in the report, and generate refuses
        assertEquals("See " + ref(septic) + ".", out.sections().get(0).clauses().get(0).body());
        assertEquals(List.of("RENT -> SEPTIC"), out.brokenReferences());
        assertFalse(out.isComplete());
    }

    @Test
    void freeze_shouldResolveAReferenceIntoAnotherSection() {
        // Arrange
        TemplateClause weight = clause("20", "weight", "Under 40 lbs.", null, List.of());
        DocumentSection lease = withOrdinal(section("Lease", false, false,
                clause("10", "pets", "Pets per Section " + ref(weight) + " of the Pet Agreement.", null, List.of())), "1");
        DocumentSection pets = withOrdinal(section("Pet Agreement", false, false,
                clause("10", "count", "Two pets.", null, List.of()),
                weight), "2");

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(List.of(lease, pets), TokenValues.of(Map.of()));

        // Assert
        assertEquals("Pets per Section 2 of the Pet Agreement.",
                out.sections().get(0).clauses().get(0).body());
    }

    // ---- requires_next -------------------------------------------------------

    @Test
    void freeze_shouldKeepAPairTogether_whenTheyPrintSideBySide() {
        // Arrange -- the park-sale notice must sit directly above the signature
        TemplateClause signature = unnumbered(clause("20", "signature", "Tenant: ____", null, List.of()));
        DocumentSection lease = section("Lease", false, false,
                requiring(signature, "RCW 59.20.060",
                        clause("10", "notice", "Park may be sold.", null, List.of())),
                signature);

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(List.of(lease), TokenValues.of(Map.of()));

        // Assert
        assertTrue(out.sections().get(0).clauses().get(0).keepWithNext());
        assertFalse(out.sections().get(0).clauses().get(1).keepWithNext());
        assertTrue(out.isComplete());
    }

    @Test
    void freeze_shouldReportAPair_whenAParksClauseLandsBetweenThem() {
        // Arrange
        TemplateClause signature = unnumbered(clause("20", "signature", "Tenant: ____", null, List.of()));
        DocumentSection lease = section("Lease", false, false,
                requiring(signature, "RCW 59.20.060",
                        clause("10", "notice", "Park may be sold.", null, List.of())),
                signature);

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(lease),
                List.of(addClause(lease, "15", "No trampolines.", null, List.of())),
                List.of(),
                TokenValues.of(Map.of()));

        // Assert
        assertEquals(List.of("NOTICE -> SIGNATURE (RCW 59.20.060)"), out.separatedPairs());
        assertFalse(out.sections().get(0).clauses().get(0).keepWithNext());
        assertFalse(out.isComplete());
    }

    @Test
    void freeze_shouldReportAPair_whenTheSecondDidNotPrint() {
        // Arrange
        TemplateClause signature = unnumbered(clause("20", "signature", "Tenant: ____", null, List.of()));
        DocumentSection lease = section("Lease", false, false,
                requiring(signature, "RCW 59.20.060",
                        clause("10", "notice", "Park may be sold.", null, List.of())),
                signature);

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(lease), List.of(excludeClause(signature)), List.of(), TokenValues.of(Map.of()));

        // Assert
        assertEquals(List.of("NOTICE -> SIGNATURE (RCW 59.20.060)"), out.separatedPairs());
    }

    @Test
    void freeze_shouldKeepAPair_whenTheSecondIsAVariantOfTheOneNamed() {
        // Arrange -- two signature blocks, one per agreement type
        TemplateClause oneSigner = unnumbered(clause("20", "sign_one", "Tenant: ____",
                "term.late_fee_method", List.of("FLAT")));
        TemplateClause percent = unnumbered(variantOf(oneSigner, clause("21", "sign_pct", "Tenants: ____",
                "term.late_fee_method", List.of("PERCENT_OF_RENT"))));
        DocumentSection lease = section("Lease", false, false,
                requiring(oneSigner, null, clause("10", "notice", "Park may be sold.", null, List.of())),
                oneSigner,
                percent);

        // Act
        DocumentFreeze.Frozen out = DocumentFreeze.freeze(
                List.of(lease), TokenValues.of(Map.of("term.late_fee_method", "PERCENT_OF_RENT")));

        // Assert
        assertTrue(out.sections().get(0).clauses().get(0).keepWithNext());
        assertTrue(out.isComplete());
    }

    private static List<String> shownOf(DocumentFreeze.Frozen out, int section) {
        return out.sections().get(section).clauses().stream()
                .map(DocumentFreeze.FrozenClause::number).collect(java.util.stream.Collectors.toList());
    }

    private static List<String> labelsOf(DocumentFreeze.Frozen out, int section) {
        return out.sections().get(section).clauses().stream()
                .map(DocumentFreeze.FrozenClause::label).collect(java.util.stream.Collectors.toList());
    }

    private static List<Integer> depthsOf(DocumentFreeze.Frozen out, int section) {
        return out.sections().get(section).clauses().stream()
                .map(DocumentFreeze.FrozenClause::depth).toList();
    }

    private static List<Integer> numbersOf(DocumentFreeze.Frozen out, int section) {
        return out.sections().get(section).clauses().stream()
                .map(c -> c.ordinal().intValue()).toList();
    }

    private static List<String> keysOf(DocumentFreeze.Frozen out, int section) {
        return out.sections().get(section).clauses().stream()
                .map(DocumentFreeze.FrozenClause::clauseKey).toList();
    }

    // ---- Fixtures ------------------------------------------------------------

    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

    private static TemplateClause clause(String ordinal, String key, String body,
                                         String conditionField, List<String> conditionValues) {
        return new TemplateClause(UUID.randomUUID(), new BigDecimal(ordinal), key,
                key.toUpperCase(), body, conditionField, conditionValues,
                false, null, null, NOW, null,
                null, null, true, null, null);
    }

    private static TemplateClause softDeleted(TemplateClause c) {
        return new TemplateClause(c.uuid(), c.ordinal(), c.clauseKey(),
                c.title(), c.body(), c.conditionField(), c.conditionValues(),
                c.required(), c.statuteRef(), c.note(), c.createdAt(), NOW,
                c.parent(), c.variantOf(), c.numbered(), c.requiresNext(), c.style());
    }

    private static TemplateClause under(TemplateClause parent, TemplateClause c) {
        return new TemplateClause(c.uuid(), c.ordinal(), c.clauseKey(),
                c.title(), c.body(), c.conditionField(), c.conditionValues(),
                c.required(), c.statuteRef(), c.note(), c.createdAt(), c.deletedAt(),
                parent.uuid(), c.variantOf(), c.numbered(), c.requiresNext(), c.style());
    }

    private static TemplateClause variantOf(TemplateClause primary, TemplateClause c) {
        return new TemplateClause(c.uuid(), c.ordinal(), c.clauseKey(),
                c.title(), c.body(), c.conditionField(), c.conditionValues(),
                c.required(), c.statuteRef(), c.note(), c.createdAt(), c.deletedAt(),
                c.parent(), primary.uuid(), c.numbered(), c.requiresNext(), c.style());
    }

    private static TemplateClause unnumbered(TemplateClause c) {
        return new TemplateClause(c.uuid(), c.ordinal(), c.clauseKey(),
                c.title(), c.body(), c.conditionField(), c.conditionValues(),
                c.required(), c.statuteRef(), c.note(), c.createdAt(), c.deletedAt(),
                c.parent(), c.variantOf(), false, c.requiresNext(), c.style());
    }

    private static TemplateClause requiring(TemplateClause next, String statuteRef, TemplateClause c) {
        return new TemplateClause(c.uuid(), c.ordinal(), c.clauseKey(),
                c.title(), c.body(), c.conditionField(), c.conditionValues(),
                c.required(), statuteRef, c.note(), c.createdAt(), c.deletedAt(),
                c.parent(), c.variantOf(), c.numbered(), next.uuid(), c.style());
    }

    private static String ref(TemplateClause clause) {
        return "{{ref:" + clause.uuid() + "}}";
    }

    private static DocumentSection section(String name, boolean signatureBlock, boolean required,
                                           TemplateClause... clauses) {
        return section(name, signatureBlock, required, null, clauses);
    }

    private static DocumentSection section(String name, boolean signatureBlock, boolean required,
                                           String statuteRef, TemplateClause... clauses) {
        return new DocumentSection(UUID.randomUUID(), BigDecimal.ONE, name,
                name.toLowerCase().replace(' ', '_'), signatureBlock, false, required,
                statuteRef, null, NOW, null, List.of(clauses), null, null, null, null);
    }

    private static DocumentSection withOrdinal(DocumentSection section, String ordinal) {
        return new DocumentSection(section.uuid(), new BigDecimal(ordinal), section.name(),
                section.sectionKey(), section.signatureBlock(), section.listedAsAddendum(),
                section.required(), section.statuteRef(), section.note(),
                section.createdAt(), section.deletedAt(), section.clauses(),
                section.numberFormats(), section.citeFormats(), section.style(), section.titleStyle());
    }

    private static DocumentSection listedAddendum(DocumentSection s) {
        return new DocumentSection(s.uuid(), s.ordinal(), s.name(), s.sectionKey(), s.signatureBlock(), true,
                s.required(), s.statuteRef(), s.note(), s.createdAt(), s.deletedAt(), s.clauses(),
                s.numberFormats(), s.citeFormats(), s.style(), s.titleStyle());
    }

    private static DocumentSection withFormats(DocumentSection section, List<String> numberFormats,
                                               List<String> citeFormats) {
        return new DocumentSection(section.uuid(), section.ordinal(), section.name(),
                section.sectionKey(), section.signatureBlock(), section.listedAsAddendum(),
                section.required(), section.statuteRef(), section.note(),
                section.createdAt(), section.deletedAt(), section.clauses(),
                numberFormats, citeFormats, null, null);
    }

    private static PropertyDocumentCustomization excludeSection(DocumentSection section) {
        return customization(CustomizationAction.EXCLUDE_SECTION,
                section.uuid(), null, null, null, null, List.of());
    }

    private static PropertyDocumentCustomization excludeClause(TemplateClause clause) {
        return customization(CustomizationAction.EXCLUDE_CLAUSE,
                null, clause.uuid(), null, null, null, List.of());
    }

    private static PropertyDocumentCustomization addClause(DocumentSection section, String ordinal,
                                                           String body, String conditionField,
                                                           List<String> conditionValues) {
        return customization(CustomizationAction.ADD_CLAUSE, section.uuid(), null,
                new BigDecimal(ordinal), body, conditionField, conditionValues);
    }

    private static PropertyDocumentCustomization addClauseUnder(TemplateClause parent, DocumentSection section,
                                                                String ordinal, String body) {
        PropertyDocumentCustomization c = addClause(section, ordinal, body, null, List.of());
        return new PropertyDocumentCustomization(c.uuid(), c.assignment(), c.action(), c.section(),
                c.clause(), c.ordinal(), c.title(), c.body(), c.conditionField(),
                c.conditionValues(), c.note(), c.createdAt(), c.deletedAt(), parent.uuid());
    }

    private static PropertyDocumentCustomization customization(CustomizationAction action,
                                                               UUID section, UUID clause,
                                                               BigDecimal ordinal, String body,
                                                               String conditionField,
                                                               List<String> conditionValues) {
        return new PropertyDocumentCustomization(UUID.randomUUID(), UUID.randomUUID(), action,
                section, clause, ordinal, null, body, conditionField, conditionValues,
                null, NOW, null, null);
    }

    private static PropertyDocumentCustomization softDeleted(PropertyDocumentCustomization c) {
        return new PropertyDocumentCustomization(c.uuid(), c.assignment(), c.action(), c.section(),
                c.clause(), c.ordinal(), c.title(), c.body(), c.conditionField(),
                c.conditionValues(), c.note(), c.createdAt(), NOW, c.parent());
    }

    private static InstrumentAddition typedClause(DocumentSection section, String ordinal, String body) {
        return new InstrumentAddition(UUID.randomUUID(), UUID.randomUUID(), section.uuid(),
                new BigDecimal(ordinal), null, body, null, NOW, null, null);
    }

    private static InstrumentAddition softDeleted(InstrumentAddition addition) {
        return new InstrumentAddition(addition.uuid(), addition.instrument(), addition.section(),
                addition.ordinal(), addition.title(), addition.body(), addition.note(),
                addition.createdAt(), NOW, addition.parent());
    }
}

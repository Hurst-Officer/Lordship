package io.github.lordship.documenttemplate;

import io.github.lordship.shared.AgreementType;
import io.github.lordship.shared.DomainProblem.Problem;
import io.github.lordship.shared.InstrumentType;
import io.github.lordship.shared.StyleTarget;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ClauseLinksTest {

    // ---- parent --------------------------------------------------------------

    @Test
    void problemsWith_shouldRefuseAParent_inAnotherSection() {
        // Arrange
        Clause water = clause("10", "Water");
        Clause valve = clause("11", "Valve").parent(water);
        DocumentTemplate doc = doc(section("Lease", water), section("Rules", valve));

        // Act
        List<Problem> problems = ClauseLinks.problemsWith(doc, valve.uuid());

        // Assert
        assertEquals(List.of("clause.parent_not_in_section"), codes(problems));
        assertEquals("parent", problems.get(0).field());
    }

    @Test
    void problemsWith_shouldRefuseAParentLoop() {
        // Arrange -- A under B, B under A
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Clause first = clause(a, "10", "A").parent(b);
        Clause second = clause(b, "20", "B").parent(a);
        DocumentTemplate doc = doc(section("Rules", first, second));

        // Act / Assert
        assertEquals(List.of("clause.parent_cycle"), codes(ClauseLinks.problemsWith(doc, a)));
    }

    @Test
    void problemsWith_shouldRefuseAFourthLevel() {
        // Arrange
        Clause one = clause("10", "One");
        Clause two = clause("11", "Two").parent(one);
        Clause three = clause("12", "Three").parent(two);
        Clause four = clause("13", "Four").parent(three);
        DocumentTemplate doc = doc(section("Rules", one, two, three, four));

        // Act / Assert
        assertEquals(List.of(), codes(ClauseLinks.problemsWith(doc, three.uuid())));
        assertEquals(List.of("clause.too_deep"), codes(ClauseLinks.problemsWith(doc, four.uuid())));
    }

    // ---- variants ------------------------------------------------------------

    @Test
    void problemsWith_shouldRefuseAVariantOfAVariant() {
        // Arrange -- point every variant at the first one
        Clause bank = clause("10", "NSF bank");
        Clause flat = clause("11", "NSF flat").variantOf(bank);
        Clause third = clause("12", "NSF other").variantOf(flat);
        DocumentTemplate doc = doc(section("Lease", bank, flat, third));

        // Act / Assert
        assertEquals(List.of("clause.variant_of_a_variant"), codes(ClauseLinks.problemsWith(doc, third.uuid())));
    }

    @Test
    void problemsWith_shouldRefuseAVariant_underADifferentParent() {
        // Arrange -- variants replace each other in place, so they share a parent
        Clause fees = clause("10", "Fees");
        Clause bank = clause("11", "NSF bank").parent(fees);
        Clause flat = clause("20", "NSF flat").variantOf(bank);
        DocumentTemplate doc = doc(section("Lease", fees, bank, flat));

        // Act / Assert
        assertEquals(List.of("clause.variant_parent_differs"), codes(ClauseLinks.problemsWith(doc, flat.uuid())));
    }

    // ---- keep with next ------------------------------------------------------

    @Test
    void problemsWith_shouldAcceptAPair_sideBySide() {
        // Arrange
        Clause signature = clause("20", "Signature");
        Clause notice = clause("10", "Notice").requiresNext(signature);
        DocumentTemplate doc = doc(section("Lease", notice, signature));

        // Act / Assert
        assertEquals(List.of(), ClauseLinks.problemsWith(doc, notice.uuid()));
    }

    @Test
    void problemsWith_shouldRefuseAPair_withAClauseBetween() {
        // Arrange
        Clause signature = clause("30", "Signature");
        Clause notice = clause("10", "Notice").requiresNext(signature);
        DocumentTemplate doc = doc(section("Lease", notice, clause("20", "Trampolines"), signature));

        // Act
        List<Problem> problems = ClauseLinks.problemsWith(doc, notice.uuid());

        // Assert -- names all three, so the admin knows what to move
        assertEquals(List.of("clause.pair_interrupted"), codes(problems));
        assertEquals(List.of("Notice", "Signature", "Trampolines"), problems.get(0).args());
    }

    @Test
    void problemsWith_shouldRefuseAPair_whenTheFirstHasSubClauses() {
        // Arrange -- its A, B, C would print between it and the signature
        Clause signature = clause("30", "Signature");
        Clause notice = clause("10", "Notice").requiresNext(signature);
        DocumentTemplate doc = doc(section("Lease", notice, clause("11", "A").parent(notice), signature));

        // Act / Assert
        assertEquals(List.of("clause.pair_interrupted"), codes(ClauseLinks.problemsWith(doc, notice.uuid())));
    }

    @Test
    void problemsWith_shouldAcceptAPair_withOnlyVariantsBetween() {
        // Arrange -- two signature variants; only one prints for any deal
        Clause oneSigner = clause("20", "One signer");
        Clause twoSigners = clause("21", "Two signers").variantOf(oneSigner);
        Clause notice = clause("10", "Notice").requiresNext(twoSigners);
        DocumentTemplate doc = doc(section("Lease", notice, oneSigner, twoSigners));

        // Act / Assert
        assertEquals(List.of(), ClauseLinks.problemsWith(doc, notice.uuid()));
    }

    @Test
    void problemsWith_shouldRefuseAPair_whoseSecondIsAbove() {
        // Arrange
        Clause signature = clause("10", "Signature");
        Clause notice = clause("20", "Notice").requiresNext(signature);
        DocumentTemplate doc = doc(section("Lease", signature, notice));

        // Act / Assert
        assertEquals(List.of("clause.next_is_above"), codes(ClauseLinks.problemsWith(doc, notice.uuid())));
    }

    @Test
    void brokenPairsIn_shouldCatchAClauseMovedBetweenSomeoneElsesPair() {
        // Arrange -- the rule clause is the one that moved, not the notice
        Clause signature = clause("30", "Signature");
        Clause notice = clause("10", "Notice").requiresNext(signature);
        DocumentSection lease = section("Lease", notice, clause("15", "Moved here"), signature);
        DocumentTemplate doc = doc(lease);

        // Act / Assert
        assertEquals(List.of("clause.pair_interrupted"), codes(ClauseLinks.brokenPairsIn(doc, lease.uuid())));
    }

    // ---- references and styles -----------------------------------------------

    @Test
    void problemsWith_shouldRefuseAReference_toAClauseOfAnotherDocument() {
        // Arrange
        UUID elsewhere = UUID.randomUUID();
        Clause rent = clause("10", "Rent").body("See {{ref:" + elsewhere + "}}.");
        DocumentTemplate doc = doc(section("Lease", rent));

        // Act
        List<Problem> problems = ClauseLinks.problemsWith(doc, rent.uuid());

        // Assert
        assertEquals(List.of("clause.ref_not_in_document"), codes(problems));
        assertEquals("body", problems.get(0).field());
    }

    @Test
    void problemsWith_shouldRefuseAPageWideStyle_onAClause() {
        // Arrange -- a targeted style restyles every page; a clause picks named ones
        DocumentStyle pageWide = style("titles", "font-size: 14pt;", StyleTarget.SECTION_TITLE);
        Clause rent = clause("10", "Rent").style(pageWide);
        DocumentTemplate doc = doc(List.of(pageWide), section("Lease", rent));

        // Act / Assert
        assertEquals(List.of("clause.style_not_in_document"), codes(ClauseLinks.problemsWith(doc, rent.uuid())));
    }

    // ---- delete --------------------------------------------------------------

    @Test
    void deleteBlocker_shouldNameWhatStillLeansOnTheClause() {
        // Arrange
        Clause water = clause("10", "Water");
        Clause pets = clause("20", "Pets");
        Clause signature = clause("40", "Signature");
        Clause bank = clause("50", "NSF bank");
        DocumentTemplate doc = doc(section("Lease",
                water, clause("11", "Valve").parent(water),
                pets, clause("30", "Rent").body("See {{ref:" + pets.uuid() + "}}."),
                clause("35", "Notice").requiresNext(signature), signature,
                bank, clause("51", "NSF flat").variantOf(bank),
                clause("60", "Alone")));

        // Act / Assert
        assertEquals(Optional.of(Problem.of("clause.has_sub_clauses", "Valve")), ClauseLinks.deleteBlocker(doc, water.uuid()));
        assertEquals(Optional.of(Problem.of("clause.cited_by", "Rent")), ClauseLinks.deleteBlocker(doc, pets.uuid()));
        assertEquals(Optional.of(Problem.of("clause.kept_with_by", "Notice")), ClauseLinks.deleteBlocker(doc, signature.uuid()));
        assertEquals(Optional.of(Problem.of("clause.has_variants", "NSF flat")), ClauseLinks.deleteBlocker(doc, bank.uuid()));
        assertEquals(Optional.empty(), ClauseLinks.deleteBlocker(doc, clauseNamed(doc, "Alone")));
    }

    // ---- a park excluding ----------------------------------------------------

    @Test
    void exclusionBlocker_shouldRefuse_whenAClauseTheParkKeepsCitesIt() {
        // Arrange
        Clause septic = clause("20", "Septic");
        DocumentTemplate doc = doc(section("Lease",
                clause("10", "Rent").body("See {{ref:" + septic.uuid() + "}}."), septic));

        // Act
        Optional<Problem> blocker = ClauseLinks.exclusionBlocker(doc, Set.of(septic.uuid()), Set.of(), "clause");

        // Assert
        assertEquals(Optional.of(Problem.of("customization.clause_is_cited", "Rent")), blocker);
    }

    @Test
    void exclusionBlocker_shouldAllowIt_whenAnotherVariantWillPrintInstead() {
        // Arrange
        Clause bank = clause("20", "NSF bank");
        Clause flat = clause("21", "NSF flat").variantOf(bank);
        DocumentTemplate doc = doc(section("Lease",
                clause("10", "Rent").body("See {{ref:" + bank.uuid() + "}}."), bank, flat));

        // Act / Assert
        assertEquals(Optional.empty(), ClauseLinks.exclusionBlocker(doc, Set.of(bank.uuid()), Set.of(), "clause"));
    }

    @Test
    void exclusionBlocker_shouldCountSubClauses_asGoingWithTheirParent() {
        // Arrange -- the cited clause is a child of the one being excluded
        Clause water = clause("20", "Water");
        Clause faucets = clause("21", "Faucets").parent(water);
        DocumentTemplate doc = doc(section("Lease",
                clause("10", "Rent").body("See {{ref:" + faucets.uuid() + "}}."), water, faucets));

        // Act / Assert
        assertEquals(Optional.of(Problem.of("customization.clause_is_cited", "Rent")),
                ClauseLinks.exclusionBlocker(doc, Set.of(water.uuid()), Set.of(), "clause"));
    }

    @Test
    void exclusionBlocker_shouldRefuseASection_holdingAClauseTheLeaseCites() {
        // Arrange -- the lease cites the pet weight rule in the Pet Agreement
        Clause weight = clause("10", "Weight");
        DocumentSection pets = section("Pet Agreement", weight);
        DocumentTemplate doc = doc(section("Lease",
                clause("10", "Pets").body("Per Section {{ref:" + weight.uuid() + "}}.")), pets);

        // Act / Assert
        assertEquals(Optional.of(Problem.of("customization.section_is_cited", "Pets")),
                ClauseLinks.exclusionBlocker(doc, ClauseLinks.clausesOf(doc, pets.uuid()), Set.of(), "section"));
    }

    // ---- a park's own clause -------------------------------------------------

    @Test
    void problemsWithParkClause_shouldRefuseLandingBetweenAPair() {
        // Arrange
        Clause signature = clause("20", "Signature");
        DocumentSection lease = section("Lease", clause("10", "Notice").requiresNext(signature), signature);
        DocumentTemplate doc = doc(lease);

        // Act
        List<Problem> problems = ClauseLinks.problemsWithParkClause(doc, parkClause(lease, "15", null, "No trampolines."));

        // Assert
        assertEquals(List.of("customization.interrupts_pair"), codes(problems));
        assertEquals("ordinal", problems.get(0).field());
    }

    @Test
    void problemsWithParkClause_shouldAcceptARuleUnderATemplateClause() {
        // Arrange
        Clause water = clause("10", "Water");
        DocumentSection rules = section("Rules", water, clause("11", "Valve").parent(water));
        DocumentTemplate doc = doc(rules);

        // Act / Assert
        assertEquals(List.of(), ClauseLinks.problemsWithParkClause(doc, parkClause(rules, "12", water.uuid(), "No hoses.")));
    }

    @Test
    void problemsWithParkClause_shouldRefuseAParentFromAnotherSection() {
        // Arrange
        Clause water = clause("10", "Water");
        DocumentSection lease = section("Lease", water);
        DocumentSection rules = section("Rules", clause("10", "Noise"));
        DocumentTemplate doc = doc(lease, rules);

        // Act / Assert
        assertEquals(List.of("customization.parent_not_in_section"),
                codes(ClauseLinks.problemsWithParkClause(doc, parkClause(rules, "20", water.uuid(), "No hoses."))));
    }

    // ---- sections and styles -------------------------------------------------

    @Test
    void problemsWithSection_shouldRefuseAFormatThatDoesNotNumberItsLevel() {
        // Arrange -- level 2 printed as "9." would number A, B and C alike
        DocumentSection rules = withFormats(section("Rules"), List.of("{1}.", "{1}."), List.of("{1}", "{1}{2:A}"));

        // Act
        List<Problem> problems = ClauseLinks.problemsWithSection(doc(rules), rules.uuid());

        // Assert
        assertEquals(List.of("section.format_invalid"), codes(problems));
        assertEquals(List.of(2, "{1}.", "{2} missing"), problems.get(0).args());
        assertEquals("number_formats", problems.get(0).field());
    }

    @Test
    void problemsWithSection_shouldRefuseMoreThanThreeLevels() {
        DocumentSection rules = withFormats(section("Rules"),
                List.of("{1}.", "{2:A}.", "({3:i})", "{4}"), DocumentSection.DEFAULT_CITE_FORMATS);
        assertEquals(List.of("section.formats_count"), codes(ClauseLinks.problemsWithSection(doc(rules), rules.uuid())));
    }

    @Test
    void problemsWithStyle_shouldRefuseCssThatLeavesItsRule() {
        List<Problem> problems = ClauseLinks.problemsWithStyle(style("bad", "color: red } body { x: y", null));
        assertEquals(List.of("style.css_not_allowed"), codes(problems));
        assertEquals("css", problems.get(0).field());
    }

    @Test
    void duplicateTarget_shouldRefuseASecondStyleForTheSamePartOfThePage() {
        // Arrange
        DocumentStyle first = style("titles", "font-size: 14pt;", StyleTarget.SECTION_TITLE);
        DocumentStyle second = style("bigger titles", "font-size: 16pt;", StyleTarget.SECTION_TITLE);
        DocumentTemplate doc = doc(List.of(first, second));

        // Act / Assert
        assertEquals(Optional.of(Problem.of("style.target_taken", "SECTION_TITLE", "titles")),
                ClauseLinks.duplicateTarget(doc, second));
        assertEquals(Optional.empty(), ClauseLinks.duplicateTarget(doc, style("notice", "font-weight: bold;", null)));
    }

    // ---- fixtures ------------------------------------------------------------

    private static final OffsetDateTime NOW = OffsetDateTime.now();

    /** A clause built in steps, so each test says only the link it is about. */
    private record Clause(UUID uuid, String ordinal, String title, String body,
                          UUID parent, UUID variantOf, UUID requiresNext, UUID style) {
        Clause parent(Clause p) { return new Clause(uuid, ordinal, title, body, p.uuid(), variantOf, requiresNext, style); }
        Clause parent(UUID p) { return new Clause(uuid, ordinal, title, body, p, variantOf, requiresNext, style); }
        Clause variantOf(Clause v) { return new Clause(uuid, ordinal, title, body, parent, v.uuid(), requiresNext, style); }
        Clause requiresNext(Clause n) { return new Clause(uuid, ordinal, title, body, parent, variantOf, n.uuid(), style); }
        Clause body(String b) { return new Clause(uuid, ordinal, title, b, parent, variantOf, requiresNext, style); }
        Clause style(DocumentStyle s) { return new Clause(uuid, ordinal, title, body, parent, variantOf, requiresNext, s.uuid()); }
    }

    private static Clause clause(String ordinal, String title) {
        return new Clause(UUID.randomUUID(), ordinal, title, title + ".", null, null, null, null);
    }

    private static Clause clause(UUID uuid, String ordinal, String title) {
        return new Clause(uuid, ordinal, title, title + ".", null, null, null, null);
    }

    private static TemplateClause built(Clause c) {
        return new TemplateClause(c.uuid(), new BigDecimal(c.ordinal()), null, c.title(), c.body(),
                null, List.of(), false, null, null, NOW, null,
                c.parent(), c.variantOf(), true, c.requiresNext(), c.style());
    }

    private static DocumentSection section(String name, Object... clauses) {
        List<TemplateClause> built = new ArrayList<>();
        for (Object c : clauses) {
            built.add(c instanceof Clause clause ? built(clause) : (TemplateClause) c);
        }
        return new DocumentSection(UUID.randomUUID(), BigDecimal.ONE, name, null, false, false, false,
                null, null, NOW, null, built, null, null, null, null);
    }

    private static DocumentSection withFormats(DocumentSection s, List<String> numbers, List<String> cites) {
        return new DocumentSection(s.uuid(), s.ordinal(), s.name(), s.sectionKey(), s.signatureBlock(),
                s.listedAsAddendum(), s.required(), s.statuteRef(), s.note(), s.createdAt(), s.deletedAt(),
                s.clauses(), numbers, cites, s.style(), s.titleStyle());
    }

    private static DocumentStyle style(String name, String css, StyleTarget target) {
        return new DocumentStyle(UUID.randomUUID(), name, css, target, null, NOW, null);
    }

    private static DocumentTemplate doc(DocumentSection... sections) {
        return doc(List.of(), sections);
    }

    private static DocumentTemplate doc(List<DocumentStyle> styles, DocumentSection... sections) {
        return new DocumentTemplate(UUID.randomUUID(), "WA Lease", AgreementType.LAND, InstrumentType.LEASE,
                1, null, NOW, null, Arrays.asList(sections), styles);
    }

    private static PropertyDocumentCustomization parkClause(DocumentSection section, String ordinal,
                                                            UUID parent, String body) {
        return new PropertyDocumentCustomization(UUID.randomUUID(), UUID.randomUUID(),
                CustomizationAction.ADD_CLAUSE, section.uuid(), null, new BigDecimal(ordinal),
                null, body, null, List.of(), null, NOW, null, parent);
    }

    private static UUID clauseNamed(DocumentTemplate doc, String title) {
        return doc.sections().stream().flatMap(s -> s.clauses().stream())
                .filter(c -> title.equals(c.title())).findFirst().orElseThrow().uuid();
    }

    private static List<String> codes(List<Problem> problems) {
        return problems.stream().map(Problem::code).toList();
    }
}

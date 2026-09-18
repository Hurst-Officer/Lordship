package io.github.lordship.instruments;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaseDocumentTest {

    // ── Escaping ──

    @Test
    void render_shouldEscapeMarkup_whenABodyContainsIt() {
        // Arrange
        LeasePreview lease = preview(section("Lease", clause(1, "Landlord is Smith & Sons <Holdings>.")));

        // Act
        String html = LeaseDocument.render(lease, "HS-41JB-5F32-RAS");

        // Assert
        assertTrue(html.contains("Smith &amp; Sons &lt;Holdings&gt;."));
        assertFalse(html.contains("<Holdings>"));
    }

    @Test
    void render_shouldEscapeMarkup_whenASectionNameContainsIt() {
        // Arrange
        LeasePreview lease = preview(section("Rules & <Regulations>", clause(1, "Body.")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("<h2>Rules &amp; &lt;Regulations&gt;</h2>"));
    }

    @Test
    void render_shouldLeaveAnUnresolvedTokenStandingInTheText() {
        // Arrange -- a hole is shown, not hidden: the office worker finds it by
        // reading the document rather than by reading an error.
        DocumentFreeze.FrozenClause clause = new DocumentFreeze.FrozenClause(
                BigDecimal.ONE, "REMIT", null,
                "Pay to {{property.remittance_address}}.",
                "Pay to {{property.remittance_address}}.",
                null, UUID.randomUUID(), ClauseOrigin.TEMPLATE,
                List.of("property.remittance_address"));
        LeasePreview lease = preview(section("Lease", clause));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("Pay to {{property.remittance_address}}."));
    }

    // ── Numbering ──

    @Test
    void render_shouldNotPrintTheOrdinal_whenAClauseHasOne() {
        // Arrange -- the ordinal is where the clause sits, not what it is
        // called. Most seeded bodies open with a number the author typed, and
        // some cite each other by it.
        LeasePreview lease = preview(section("Lease",
                clause(1, "1. Demised Premises: Landlord leases to tenant."),
                clause(2, "5a. The rate indicated in Section 5a applies.")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("<p class=\"body\">1. Demised Premises: Landlord leases to tenant.</p>"));
        assertTrue(html.contains("<p class=\"body\">5a. The rate indicated in Section 5a applies.</p>"));
    }

    // ── Whitespace ──

    @Test
    void render_shouldPreserveBlankLines_whenABodyHasParagraphs() {
        // Arrange
        LeasePreview lease = preview(section("Lease", clause(1, "First paragraph.\n\nSecond paragraph.")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert -- reproduced verbatim; the stylesheet prints it, not markup.
        assertTrue(html.contains("<p class=\"body\">First paragraph.\n\nSecond paragraph.</p>"));
        assertFalse(html.contains("<br>"));
    }

    @Test
    void render_shouldPreserveSignatureRules_whenABodyIsASignatureLine() {
        // Arrange -- the underscores and tabs are the author's, not ours.
        String signature = "Name \t\t\t Signature\n________________________ ____________________";
        LeasePreview lease = preview(section("Lease", clause(1, signature)));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains(signature));
    }

    // ── Markup ──

    @Test
    void render_shouldPrintAMark_whenAClauseBodyCarriesOne() {
        // Arrange -- the allowlist lives in ClauseMarkup; this is the wiring
        LeasePreview lease = preview(section("Lease",
                clause(1, "Received by <b>midnight</b> on the <b>8th</b>.")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("Received by <b>midnight</b> on the <b>8th</b>."));
    }

    @Test
    void render_shouldDrawAFillInRule_whenAClauseBodyAsksForOne() {
        // Arrange
        LeasePreview lease = preview(section("Lease", clause(1, "Name <rule w=\"30\"> Date <rule w=\"12\">")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("<span class=\"rule\" style=\"width:30ch\"></span>"), html);
        assertTrue(html.contains(".rule { display: inline-block;"), html);
    }

    @Test
    void render_shouldNotAllowAMark_inASectionName() {
        // Arrange -- marks are a thing an author writes inside a clause
        LeasePreview lease = preview(section("Rules <b>and</b> Regulations", clause(1, "Body.")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("<h2>Rules &lt;b&gt;and&lt;/b&gt; Regulations</h2>"));
    }

    // ── Sections ──

    @Test
    void render_shouldSkipASection_whenItHasNoClauses() {
        // Arrange -- the freeze exempts signature blocks from its own
        // empty-section rule, and this template marks every section one.
        LeasePreview lease = preview(
                section("Lease", clause(1, "Body.")),
                section("Septic Addendum"));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("<h2>Lease</h2>"));
        assertFalse(html.contains("Septic Addendum"));
    }

    @Test
    void render_shouldBreakBeforeEverySectionButTheFirst() {
        // Arrange
        LeasePreview lease = preview(
                section("Lease", clause(1, "Body.")),
                section("Pet Agreement", clause(1, "Body.")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertEquals(1, count(html, "<section class=\"sheet first\">"));
        assertEquals(1, count(html, "<section class=\"sheet\">"));
        assertTrue(html.indexOf("sheet first") < html.indexOf("<section class=\"sheet\">"));
    }

    @Test
    void render_shouldMarkTheFirstPrintedSection_whenAnEarlierOneWasSkipped() {
        // Arrange -- "first" means the first one we printed, which is why it is
        // decided here and not by a :first-of-type rule.
        LeasePreview lease = preview(
                section("Checklist"),
                section("Lease", clause(1, "Body.")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("<section class=\"sheet first\">\n<h2>Lease</h2>"));
    }

    @Test
    void render_shouldPrintASectionStatute_whenTheSectionCitesOne() {
        // Arrange
        DocumentFreeze.FrozenSection section = new DocumentFreeze.FrozenSection(
                BigDecimal.ONE, "Rules and Regulations", "RULES", true, true, "RCW 59.20",
                List.of(clause(1, "Body.")));
        LeasePreview lease = preview(section);

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("<p class=\"statute\">RCW 59.20</p>"));
    }

    // ── Addenda checklist ──

    @Test
    void render_shouldListAnAddendum_whenASectionAsksToBeListed() {
        // Arrange
        LeasePreview lease = preview(
                section("Lease", clause(1, "Body.")),
                listed("Pet Agreement", clause(1, "Body.")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("<ul class=\"addenda\">"));
        assertTrue(html.contains("<li><span class=\"box\"></span>Pet Agreement</li>"));
        assertFalse(html.contains("<li><span class=\"box\"></span>Lease</li>"));
    }

    @Test
    void render_shouldNotListAnAddendum_whenThatSectionCameUpEmpty() {
        // Arrange -- the checklist and the packet cannot be allowed to disagree
        // about what is attached.
        LeasePreview lease = preview(
                section("Lease", clause(1, "Body.")),
                listed("Pet Agreement"));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertFalse(html.contains("<ul class=\"addenda\">"));
        assertFalse(html.contains("Pet Agreement"));
    }

    @Test
    void render_shouldOmitTheChecklist_whenNothingIsListed() {
        // Arrange
        LeasePreview lease = preview(section("Lease", clause(1, "Body.")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertFalse(html.contains("<ul class=\"addenda\">"));
    }

    // ── Draft stamp ──

    @Test
    void render_shouldStampDraft_whenThePacketHasAHoleInIt() {
        // Arrange
        DocumentFreeze.Frozen frozen = new DocumentFreeze.Frozen(
                List.of(section("Lease", clause(1, "Body."))),
                List.of("property.remittance_address"),
                List.of());

        // Act
        String html = LeaseDocument.render(preview(frozen), null);

        // Assert
        assertTrue(html.contains("DRAFT &mdash; NOT FOR SIGNATURE"));
    }

    @Test
    void render_shouldStampDraft_whenARequiredSectionWasOmitted() {
        // Arrange
        DocumentFreeze.Frozen frozen = new DocumentFreeze.Frozen(
                List.of(section("Lease", clause(1, "Body."))),
                List.of(),
                List.of("Septic / Sewer Addendum (RCW 59.20)"));

        // Act
        String html = LeaseDocument.render(preview(frozen), null);

        // Assert
        assertTrue(html.contains("DRAFT &mdash; NOT FOR SIGNATURE"));
    }

    @Test
    void render_shouldNotStampDraft_whenThePacketIsComplete() {
        // Arrange
        LeasePreview lease = preview(section("Lease", clause(1, "Body.")));

        // Act
        String html = LeaseDocument.render(lease, "HS-41JB-5F32-RAS");

        // Assert
        assertFalse(html.contains("DRAFT"));
    }

    // ── Serial ──

    @Test
    void render_shouldPutTheSerialInTheRunningFooter_whenOneIsGiven() {
        // Arrange
        LeasePreview lease = preview(section("Lease", clause(1, "Body.")));

        // Act
        String html = LeaseDocument.render(lease, "HS-41JB-5F32-RAS");

        // Assert
        assertTrue(html.contains("@bottom-right { content: \"HS-41JB-5F32-RAS\";"));
    }

    @Test
    void render_shouldLeaveTheFooterSerialEmpty_whenThereIsNoSerialYet() {
        // Arrange -- a preview has no serial; a serial is assigned at GENERATED.
        LeasePreview lease = preview(section("Lease", clause(1, "Body.")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("@bottom-right { content: \"\";"));
    }

    @Test
    void render_shouldNameTheTemplate_inTheScreenOnlyHeader() {
        // Arrange
        LeasePreview lease = preview(section("Lease", clause(1, "Body.")));

        // Act
        String html = LeaseDocument.render(lease, "HS-41JB-5F32-RAS");

        // Assert
        assertTrue(html.contains("WA Manufactured Home Lot Lease 2026 v3 &middot; HS-41JB-5F32-RAS"));
        assertTrue(html.contains("@media print { .provenance { display: none; } }"));
    }

    @Test
    void render_shouldTitleTheDocument_afterTheTemplate() {
        // Arrange
        LeasePreview lease = preview(section("Lease", clause(1, "Body.")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("<title>WA Manufactured Home Lot Lease 2026</title>"));
    }

    // ── Clause detail ──

    @Test
    void render_shouldPrintAClauseTitle_whenTheAuthorWroteOne() {
        // Arrange
        DocumentFreeze.FrozenClause clause = new DocumentFreeze.FrozenClause(
                BigDecimal.ONE, "PETS", "Pets", "No pets without written consent.", "No pets without written consent.",
                null, UUID.randomUUID(), ClauseOrigin.TEMPLATE, List.of());
        LeasePreview lease = preview(section("Lease", clause));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("<h3>Pets</h3>"));
    }

    @Test
    void render_shouldOmitTheClauseTitle_whenTheAuthorWroteNone() {
        // Arrange -- no seeded clause has one, and an empty heading is worse
        // than none.
        LeasePreview lease = preview(section("Lease", clause(1, "Body.")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertFalse(html.contains("<h3>"));
    }

    @Test
    void render_shouldPrintAClauseStatute_whenTheClauseCitesOne() {
        // Arrange
        DocumentFreeze.FrozenClause clause = new DocumentFreeze.FrozenClause(
                BigDecimal.ONE, "DEPOSIT", null, "Body.", "Body.",
                "RCW 59.20.170", UUID.randomUUID(), ClauseOrigin.TEMPLATE, List.of());
        LeasePreview lease = preview(section("Lease", clause));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("<p class=\"statute\">RCW 59.20.170</p>"));
    }

    @Test
    void render_shouldPrintAPropertyClause_theSameWayAsATemplateOne() {
        // Arrange -- origin is provenance, not presentation. A park's clause and
        // a typed-on clause print like any other, or the paper says which
        // paragraphs were added and invites an argument about them.
        DocumentFreeze.FrozenClause template = new DocumentFreeze.FrozenClause(
                BigDecimal.ONE, "A", null, "Body.", "Body.", null,
                UUID.randomUUID(), ClauseOrigin.TEMPLATE, List.of());
        DocumentFreeze.FrozenClause park = new DocumentFreeze.FrozenClause(
                BigDecimal.TWO, "B", null, "Body.", "Body.", null,
                UUID.randomUUID(), ClauseOrigin.PROPERTY, List.of());
        DocumentFreeze.FrozenClause typed = new DocumentFreeze.FrozenClause(
                BigDecimal.valueOf(3), "C", null, "Body.", "Body.", null,
                UUID.randomUUID(), ClauseOrigin.INSTRUMENT, List.of());

        // Act
        String html = LeaseDocument.render(preview(section("Lease", template, park, typed)), null);

        // Assert
        assertEquals(3, count(html, "<p class=\"body\">Body.</p>"));
        assertFalse(html.contains("PROPERTY"));
        assertFalse(html.contains("INSTRUMENT"));
    }

    // ── Fixtures ──

    private static LeasePreview preview(DocumentFreeze.FrozenSection... sections) {
        return preview(new DocumentFreeze.Frozen(List.of(sections), List.of(), List.of()));
    }

    private static LeasePreview preview(DocumentFreeze.Frozen frozen) {
        return new LeasePreview(UUID.randomUUID(), UUID.randomUUID(),
                "WA Manufactured Home Lot Lease 2026", 3, frozen);
    }

    private static DocumentFreeze.FrozenSection section(String name, DocumentFreeze.FrozenClause... clauses) {
        return new DocumentFreeze.FrozenSection(
                BigDecimal.ONE, name, name.toUpperCase().replace(' ', '_'),
                true, false, null, List.of(clauses));
    }

    private static DocumentFreeze.FrozenSection listed(String name, DocumentFreeze.FrozenClause... clauses) {
        return new DocumentFreeze.FrozenSection(
                BigDecimal.ONE, name, name.toUpperCase().replace(' ', '_'),
                true, true, null, List.of(clauses));
    }

    private static DocumentFreeze.FrozenClause clause(int ordinal, String body) {
        return new DocumentFreeze.FrozenClause(
                BigDecimal.valueOf(ordinal), "KEY_" + ordinal, null, body, body,
                null, UUID.randomUUID(), ClauseOrigin.TEMPLATE, List.of());
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }
}

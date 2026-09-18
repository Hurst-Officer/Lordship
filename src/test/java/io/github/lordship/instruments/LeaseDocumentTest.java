package io.github.lordship.instruments;

import io.github.lordship.documenttemplate.DocumentStyle;
import io.github.lordship.shared.StyleTarget;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
                BigDecimal.ONE, "REMIT", null, null, null, 1,
                "Pay to {{property.remittance_address}}.",
                "Pay to {{property.remittance_address}}.",
                null, UUID.randomUUID(), ClauseOrigin.TEMPLATE,
                List.of("property.remittance_address"), false, null);
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
        // called. Only the freeze's number prints, and these have none.
        LeasePreview lease = preview(section("Lease",
                clause(1, "1. Demised Premises: Landlord leases to tenant."),
                clause(2, "5a. The rate indicated in Section 5a applies.")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("<p class=\"body\">1. Demised Premises: Landlord leases to tenant.</p>"));
        assertTrue(html.contains("<p class=\"body\">5a. The rate indicated in Section 5a applies.</p>"));
    }

    @Test
    void render_shouldPrintTheFreezesNumber_inFrontOfTheBody() {
        // Arrange
        LeasePreview lease = preview(section("Lease", numbered("9.", 1, "Water:")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("<p class=\"body hang\"><span class=\"num\">9.</span>Water:</p>"));
    }

    @Test
    void render_shouldPrintTheNumber_inFrontOfTheTitle_whenThereIsOne() {
        // Arrange
        DocumentFreeze.FrozenClause pets = new DocumentFreeze.FrozenClause(
                BigDecimal.ONE, "PETS", "Pets", "4.", "4", 1, "Two pets.", "Two pets.",
                null, UUID.randomUUID(), ClauseOrigin.TEMPLATE, List.of(), false, null);
        LeasePreview lease = preview(section("Lease", pets));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert -- once, in front of the title, not again in front of the body
        assertTrue(html.contains("<h3><span class=\"num\">4.</span>Pets</h3>"));
        assertTrue(html.contains("<p class=\"body\">Two pets.</p>"));
    }

    @Test
    void render_shouldIndentASubClause_oneStepPerLevel() {
        // Arrange
        LeasePreview lease = preview(section("Rules",
                numbered("9.", 1, "Water:"),
                numbered("A.", 2, "Check valve."),
                numbered("(i)", 3, "Brass only.")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("<div class=\"clause depth-1\">"));
        assertTrue(html.contains("<div class=\"clause depth-2\">"));
        assertTrue(html.contains("<div class=\"clause depth-3\">"));
    }

    // ── Keep together ──

    @Test
    void render_shouldBoxAPairTogether_whenTheFirstKeepsWithTheNext() {
        // Arrange -- the park-sale notice and the signature under it
        LeasePreview lease = preview(section("Lease",
                clause(1, "Rent."),
                keeping(clause(2, "The park may be sold.")),
                clause(3, "Tenant: ____"),
                clause(4, "After.")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert -- one box holding exactly the two of them
        int open = html.indexOf("<div class=\"keep\">");
        int close = html.indexOf("</div>\n</div>\n", html.indexOf("Tenant: ____"));
        assertTrue(open > html.indexOf("Rent."));
        assertTrue(open < html.indexOf("The park may be sold."));
        assertTrue(close > html.indexOf("Tenant: ____"));
        assertTrue(close < html.indexOf("After."));
        assertEquals(1, count(html, "<div class=\"keep\">"));
    }

    @Test
    void render_shouldCloseTheBox_whenTheSectionEndsInsideIt() {
        // Arrange -- the freeze reported this pair; the page must still be well formed
        LeasePreview lease = preview(section("Lease", keeping(clause(1, "The park may be sold."))));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertEquals(count(html, "<div"), count(html, "</div>"));
    }

    // ── Admin styles ──

    @Test
    void render_shouldEmitAnAdminStyle_forTheClauseThatUsesIt() {
        // Arrange
        UUID notice = UUID.randomUUID();
        LeasePreview lease = preview(section("Lease", styled(notice, clause(1, "The park may be sold."))));

        // Act
        String html = LeaseDocument.render(lease, null,
                List.of(named(notice, "font-weight: bold; font-size: 13pt; border: 2px solid #000;")));

        // Assert
        String cls = LeaseDocument.styleClass(notice);
        assertTrue(html.contains("." + cls + " { font-weight: bold; font-size: 13pt; border: 2px solid #000; }"));
        assertTrue(html.contains("<div class=\"clause depth-1 " + cls + "\">"));
    }

    @Test
    void render_shouldLeaveOutAStyle_noPrintedClauseUses() {
        // Arrange
        UUID unused = UUID.randomUUID();
        LeasePreview lease = preview(section("Lease", clause(1, "Rent.")));

        // Act
        String html = LeaseDocument.render(lease, null, List.of(named(unused, "color: red;")));

        // Assert
        assertFalse(html.contains(LeaseDocument.styleClass(unused)));
    }

    @Test
    void render_shouldDropAStyle_thatTriesToLeaveItsRule() {
        // Arrange -- a closing brace would let a style restyle the whole lease
        UUID bad = UUID.randomUUID();
        LeasePreview lease = preview(section("Lease", styled(bad, clause(1, "Rent."))));

        // Act
        String html = LeaseDocument.render(lease, null, List.of(named(bad, "color: red } body { display: none")));

        // Assert -- no rule for it at all, and the clause still prints
        assertFalse(html.contains("color: red"));
        assertFalse(html.contains("." + LeaseDocument.styleClass(bad) + " {"));
        assertTrue(html.contains("Rent."));
    }

    @Test
    void render_shouldRestyleEverySectionTitle_whenTheAdminTargetsIt() {
        // Arrange -- the admin's edit to the built-in look
        LeasePreview lease = preview(section("Lease", clause(1, "Rent.")));

        // Act
        String html = LeaseDocument.render(lease, null,
                List.of(targeted(StyleTarget.SECTION_TITLE, "font-size: 16pt; border-bottom: none;"),
                        targeted(StyleTarget.PAGE, "margin: 25mm;")));

        // Assert -- after the built-in rules, so the admin's wins
        int builtIn = html.indexOf("h2 { font-size: 12.5pt");
        int admin = html.indexOf("h2 { font-size: 16pt; border-bottom: none; }");
        assertTrue(builtIn >= 0 && admin > builtIn);
        assertTrue(html.contains("@page { margin: 25mm; }"));
    }

    @Test
    void render_shouldStyleASection_andItsTitleSeparately() {
        // Arrange
        UUID whole = UUID.randomUUID();
        UUID title = UUID.randomUUID();
        DocumentFreeze.FrozenSection rules = new DocumentFreeze.FrozenSection(
                BigDecimal.ONE, "Rules", "RULES", true, false, null,
                List.of(clause(1, "No trampolines.")), whole, title);

        // Act
        String html = LeaseDocument.render(preview(rules), null,
                List.of(named(whole, "margin-left: 6mm;"), named(title, "font-size: 14pt;")));

        // Assert
        assertTrue(html.contains("<section class=\"sheet first " + LeaseDocument.styleClass(whole) + "\">"));
        assertTrue(html.contains("<h2 class=\"" + LeaseDocument.styleClass(title) + "\">Rules</h2>"));
        assertTrue(html.contains("." + LeaseDocument.styleClass(title) + " { font-size: 14pt; }"));
    }

    @Test
    void render_shouldIgnoreADeletedStyle() {
        // Arrange
        LeasePreview lease = preview(section("Lease", clause(1, "Rent.")));
        DocumentStyle gone = new DocumentStyle(UUID.randomUUID(), "old", "color: red;",
                StyleTarget.BODY, null, null, java.time.OffsetDateTime.now());

        // Act
        String html = LeaseDocument.render(lease, null, List.of(gone));

        // Assert
        assertFalse(html.contains("color: red"));
    }

    @Test
    void styleClass_shouldBeLettersAndDigitsOnly() {
        assertTrue(LeaseDocument.styleClass(UUID.randomUUID()).matches("s-[0-9a-f]{32}"));
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
                List.of(clause(1, "Body.")), null, null);
        LeasePreview lease = preview(section);

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("<p class=\"statute\">RCW 59.20</p>"));
    }

    // ── Addenda ──

    @Test
    void render_shouldNotDrawAnAddendaListOfItsOwn() {
        // Arrange -- the list belongs where the author puts {{packet.addenda}},
        // not above the first page where it pushed everything down
        LeasePreview lease = preview(
                section("Lease", clause(1, "Body.")),
                listed("Pet Agreement", clause(1, "Body.")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertFalse(html.contains("class=\"addenda\""));
    }

    // ── Lists and tables ──

    @Test
    void render_shouldPrintABodyWithAList_asABlockRatherThanAParagraph() {
        // Arrange
        LeasePreview lease = preview(section("Checklist", clause(1, "Tenant agrees:\n- to sign the lease\n- to the rules")));

        // Act
        String html = LeaseDocument.render(lease, null);

        // Assert
        assertTrue(html.contains("<div class=\"body\"><div class=\"seg\">Tenant agrees:</div>"
                + "<ul class=\"bullets\"><li>to sign the lease</li><li>to the rules</li></ul></div>"));
    }

    // ── Draft stamp ──

    @Test
    void render_shouldStampDraft_whenThePacketHasAHoleInIt() {
        // Arrange
        DocumentFreeze.Frozen frozen = new DocumentFreeze.Frozen(
                List.of(section("Lease", clause(1, "Body."))),
                List.of("property.remittance_address"),
                List.of(), List.of(), List.of());

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
                List.of("Septic / Sewer Addendum (RCW 59.20)"), List.of(), List.of());

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
                BigDecimal.ONE, "PETS", "Pets", null, null, 1, "No pets without written consent.", "No pets without written consent.",
                null, UUID.randomUUID(), ClauseOrigin.TEMPLATE, List.of(), false, null);
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
                BigDecimal.ONE, "DEPOSIT", null, null, null, 1, "Body.", "Body.",
                "RCW 59.20.170", UUID.randomUUID(), ClauseOrigin.TEMPLATE, List.of(), false, null);
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
                BigDecimal.ONE, "A", null, null, null, 1, "Body.", "Body.", null,
                UUID.randomUUID(), ClauseOrigin.TEMPLATE, List.of(), false, null);
        DocumentFreeze.FrozenClause park = new DocumentFreeze.FrozenClause(
                BigDecimal.TWO, "B", null, null, null, 1, "Body.", "Body.", null,
                UUID.randomUUID(), ClauseOrigin.PROPERTY, List.of(), false, null);
        DocumentFreeze.FrozenClause typed = new DocumentFreeze.FrozenClause(
                BigDecimal.valueOf(3), "C", null, null, null, 1, "Body.", "Body.", null,
                UUID.randomUUID(), ClauseOrigin.INSTRUMENT, List.of(), false, null);

        // Act
        String html = LeaseDocument.render(preview(section("Lease", template, park, typed)), null);

        // Assert
        assertEquals(3, count(html, "<p class=\"body\">Body.</p>"));
        assertFalse(html.contains("PROPERTY"));
        assertFalse(html.contains("INSTRUMENT"));
    }

    // ── Fixtures ──

    private static LeasePreview preview(DocumentFreeze.FrozenSection... sections) {
        return preview(new DocumentFreeze.Frozen(List.of(sections), List.of(), List.of(), List.of(), List.of()));
    }

    private static LeasePreview preview(DocumentFreeze.Frozen frozen) {
        return new LeasePreview(UUID.randomUUID(), UUID.randomUUID(),
                "WA Manufactured Home Lot Lease 2026", 3, frozen);
    }

    private static DocumentFreeze.FrozenSection section(String name, DocumentFreeze.FrozenClause... clauses) {
        return new DocumentFreeze.FrozenSection(
                BigDecimal.ONE, name, name.toUpperCase().replace(' ', '_'),
                true, false, null, List.of(clauses), null, null);
    }

    private static DocumentFreeze.FrozenSection listed(String name, DocumentFreeze.FrozenClause... clauses) {
        return new DocumentFreeze.FrozenSection(
                BigDecimal.ONE, name, name.toUpperCase().replace(' ', '_'),
                true, true, null, List.of(clauses), null, null);
    }

    private static DocumentFreeze.FrozenClause clause(int ordinal, String body) {
        return new DocumentFreeze.FrozenClause(
                BigDecimal.valueOf(ordinal), "KEY_" + ordinal, null, null, null, 1, body, body,
                null, UUID.randomUUID(), ClauseOrigin.TEMPLATE, List.of(), false, null);
    }

    private static DocumentFreeze.FrozenClause numbered(String number, int depth, String body) {
        return new DocumentFreeze.FrozenClause(
                BigDecimal.ONE, "KEY", null, number, number, depth, body, body,
                null, UUID.randomUUID(), ClauseOrigin.TEMPLATE, List.of(), false, null);
    }

    private static DocumentFreeze.FrozenClause keeping(DocumentFreeze.FrozenClause c) {
        return new DocumentFreeze.FrozenClause(c.ordinal(), c.clauseKey(), c.title(), c.number(), c.label(),
                c.depth(), c.body(), c.bodyTemplate(), c.statuteRef(), c.sourceClause(), c.origin(),
                c.unresolved(), true, c.style());
    }

    private static DocumentFreeze.FrozenClause styled(UUID style, DocumentFreeze.FrozenClause c) {
        return new DocumentFreeze.FrozenClause(c.ordinal(), c.clauseKey(), c.title(), c.number(), c.label(),
                c.depth(), c.body(), c.bodyTemplate(), c.statuteRef(), c.sourceClause(), c.origin(),
                c.unresolved(), c.keepWithNext(), style);
    }

    private static DocumentStyle named(UUID uuid, String css) {
        return new DocumentStyle(uuid, "style", css, null, null, null, null);
    }

    private static DocumentStyle targeted(StyleTarget target, String css) {
        return new DocumentStyle(UUID.randomUUID(), target.name().toLowerCase(), css, target, null, null, null);
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }
}

package io.github.lordship.shared;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClauseMarkupTest {

    // ---- what prints ---------------------------------------------------------

    @Test
    void toHtml_shouldEscapeEverythingByDefault() {
        // Arrange
        String body = "Landlord is Smith & Sons <Holdings>.";

        // Act
        String html = ClauseMarkup.toHtml(body);

        // Assert
        assertEquals("Landlord is Smith &amp; Sons &lt;Holdings&gt;.", html);
    }

    @Test
    void toHtml_shouldPrintBold_whenTheAuthorWroteIt() {
        // Arrange -- the real lease bolds mid-sentence, where it carries meaning
        String body = "Payment must be received by <b>midnight</b> on the 8th.";

        // Act
        String html = ClauseMarkup.toHtml(body);

        // Assert
        assertEquals("Payment must be received by <b>midnight</b> on the 8th.", html);
    }

    @Test
    void toHtml_shouldPrintItalicAndUnderline() {
        // Arrange
        String body = "An <i>Onsite Septic System</i> or <u>Onsite Water System</u>.";

        // Act
        String html = ClauseMarkup.toHtml(body);

        // Assert
        assertEquals("An <i>Onsite Septic System</i> or <u>Onsite Water System</u>.", html);
    }

    @Test
    void toHtml_shouldNestDifferentMarks() {
        // Arrange
        String body = "<b>PRIOR TO THE SALE, <u>THE EXCHANGE OF MONEY</u>, OR ANY OCCUPANCY</b>.";

        // Act
        String html = ClauseMarkup.toHtml(body);

        // Assert
        assertEquals("<b>PRIOR TO THE SALE, <u>THE EXCHANGE OF MONEY</u>, OR ANY OCCUPANCY</b>.", html);
    }

    // ---- what does not print -------------------------------------------------

    @Test
    void toHtml_shouldPrintAScriptTagAsText() {
        // Arrange -- the allowlist is what keeps a clause body from being a way
        // to put arbitrary markup on a lease
        String body = "<script>alert('x')</script>";

        // Act
        String html = ClauseMarkup.toHtml(body);

        // Assert
        assertEquals("&lt;script&gt;alert('x')&lt;/script&gt;", html);
    }

    @Test
    void toHtml_shouldNotCarryAnAttributeTheAuthorWrote() {
        // Arrange -- the one attribute on the allowlist is read as a number and
        // thrown away, so this does not parse as a rule at all
        String body = "<rule w=\"40\" onclick=\"steal()\">";

        // Act
        String html = ClauseMarkup.toHtml(body);

        // Assert -- the whole thing prints as the characters the author typed
        assertEquals("&lt;rule w=&quot;40&quot; onclick=&quot;steal()&quot;&gt;", html);
    }

    @Test
    void toHtml_shouldPrintALessThanSign_whenTheBodyMeansOne() {
        // Arrange -- a lease says "less than" sometimes, and an author typing it
        // should not have to know they are writing markup
        String body = "A pool of < 20 gal is permitted.";

        // Act
        String html = ClauseMarkup.toHtml(body);

        // Assert
        assertEquals("A pool of &lt; 20 gal is permitted.", html);
    }

    // ---- rules ---------------------------------------------------------------

    @Test
    void toHtml_shouldDrawARule_atTheWidthTheAuthorAskedFor() {
        // Arrange
        String body = "Name <rule w=\"30\"> Signature <rule w=\"40\">";

        // Act
        String html = ClauseMarkup.toHtml(body);

        // Assert -- ch rather than mm, so a rule keeps its share of the line
        // when the document's typeface changes
        assertTrue(html.contains("<span class=\"rule\" style=\"width:30ch\"></span>"), html);
        assertTrue(html.contains("<span class=\"rule\" style=\"width:40ch\"></span>"), html);
    }

    @Test
    void toHtml_shouldDrawARule_whenTheAuthorGaveNoWidth() {
        // Arrange
        String body = "Signature <rule>";

        // Act
        String html = ClauseMarkup.toHtml(body);

        // Assert
        assertTrue(html.contains("style=\"width:" + (ClauseMarkup.MAX_RULE_WIDTH / 2) + "ch\""), html);
    }

    @Test
    void toHtml_shouldClampARule_whenItWouldRunOffThePage() {
        // Arrange -- a rule is one box and cannot wrap, so an uncapped width is
        // a body that prints past the margin
        String body = "<rule w=\"999\">";

        // Act
        String html = ClauseMarkup.toHtml(body);

        // Assert
        assertTrue(html.contains("width:" + ClauseMarkup.MAX_RULE_WIDTH + "ch"), html);
    }

    // ---- marks the author got wrong ------------------------------------------

    @Test
    void toHtml_shouldCloseAMark_whenTheAuthorLeftItOpen() {
        // Arrange -- an open <b> would otherwise bold the rest of the document
        String body = "Received by <b>midnight on the 8th.";

        // Act
        String html = ClauseMarkup.toHtml(body);

        // Assert
        assertEquals("Received by <b>midnight on the 8th.</b>", html);
    }

    @Test
    void toHtml_shouldDropAClosingMark_whenNothingIsOpen() {
        // Arrange
        String body = "Received by midnight</b> on the 8th.";

        // Act
        String html = ClauseMarkup.toHtml(body);

        // Assert
        assertEquals("Received by midnight on the 8th.", html);
    }

    @Test
    void toHtml_shouldNotThrow_whateverTheBodySays() {
        // Arrange -- a body here is already frozen onto an instrument, so a mark
        // that does not parse must not stop a document that already exists
        // Act + Assert
        assertDoesNotThrow(() -> ClauseMarkup.toHtml("<b><b></i><rule w=\"x\"><<<>>>"));
        assertDoesNotThrow(() -> ClauseMarkup.toHtml(null));
    }

    // ---- validate ------------------------------------------------------------

    @Test
    void validate_shouldAcceptBalancedMarks() {
        // Arrange
        String body = "<b>1. Demised Premises:</b> Landlord leases <u>Lot 27</u>. <rule w=\"20\">";

        // Act + Assert
        assertDoesNotThrow(() -> ClauseMarkup.validate(body));
    }

    @Test
    void validate_shouldAcceptABodyWithNoMarksAtAll() {
        // Arrange -- which is most bodies
        // Act + Assert
        assertDoesNotThrow(() -> ClauseMarkup.validate("Rent is due on the 1st."));
    }

    @Test
    void validate_shouldAcceptAClauseNotWrittenYet() {
        // Arrange -- "add clause" is a button, not a form
        // Act + Assert
        assertDoesNotThrow(() -> ClauseMarkup.validate((String) null));
    }

    @Test
    void validate_shouldRejectAMarkThatIsNeverClosed() {
        // Arrange
        String body = "Received by <b>midnight on the 8th.";

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class, () -> ClauseMarkup.validate(body));

        // Assert
        assertEquals("clause.body_has_bad_markup", thrown.problem().code());
        assertEquals("markup.never_closed", thrown.details().getFirst().code());
    }

    @Test
    void validate_shouldRejectAClosingMarkWithNothingOpen() {
        // Arrange
        String body = "Received by midnight</b> on the 8th.";

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class, () -> ClauseMarkup.validate(body));

        // Assert
        assertEquals("markup.not_open", thrown.details().getFirst().code());
    }

    @Test
    void validate_shouldRejectTheSameMarkOpenedTwice() {
        // Arrange -- almost always a forgotten close rather than an intention
        String body = "<b>Rent <b>is due</b> on the 1st.</b>";

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class, () -> ClauseMarkup.validate(body));

        // Assert
        assertEquals("markup.already_open", thrown.details().getFirst().code());
    }

    @Test
    void validate_shouldRejectATagThatIsNotOnTheAllowlist() {
        // Arrange -- a near miss is worth saying out loud: "<bold>" prints as
        // the word, and the author has no reason to suspect the difference
        String body = "Received by <bold>midnight</bold>.";

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class, () -> ClauseMarkup.validate(body));

        // Assert
        assertEquals("markup.unknown_mark", thrown.details().getFirst().code());
    }

    @Test
    void validate_shouldRejectARuleWiderThanTheMargin() {
        // Arrange
        String body = "Signature <rule w=\"999\">";

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class, () -> ClauseMarkup.validate(body));

        // Assert
        assertEquals("markup.rule_width_out_of_range", thrown.details().getFirst().code());
        assertEquals(List.of(999, ClauseMarkup.MIN_RULE_WIDTH, ClauseMarkup.MAX_RULE_WIDTH),
                thrown.details().getFirst().args());
    }

    @Test
    void validate_shouldRejectARuleWidthItCannotRead() {
        // Arrange
        String body = "Signature <rule w=\"wide\">";

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class, () -> ClauseMarkup.validate(body));

        // Assert
        assertEquals("markup.rule_width_unreadable", thrown.details().getFirst().code());
    }

    @Test
    void validate_shouldAcceptALessThanSignThatIsJustText() {
        // Arrange
        // Act + Assert
        assertDoesNotThrow(() -> ClauseMarkup.validate("A pool of < 20 gal, and 5 > 3."));
    }

    @Test
    void validate_shouldReportEveryFault_notOnlyTheFirst() {
        // Arrange -- an author fixing a body should see the whole of it in one
        // pass rather than one fault per round trip
        String body = "<bold>Rent</bold> <rule w=\"999\"> <i>due";

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class, () -> ClauseMarkup.validate(body));

        // Assert
        assertEquals(4, thrown.details().size(), thrown.details().toString());
    }

    @Test
    void validate_shouldIgnoreAPatchThatDoesNotTouchTheBody() {
        // Arrange
        Map<String, Object> changes = Map.of("title", "Rent");

        // Act + Assert
        assertDoesNotThrow(() -> ClauseMarkup.validate(changes));
    }

    @Test
    void validate_shouldCheckTheBodyInsideAPatch() {
        // Arrange
        Map<String, Object> changes = Map.of("body", "Received by <b>midnight.");

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class, () -> ClauseMarkup.validate(changes));

        // Assert
        assertEquals("clause.body_has_bad_markup", thrown.problem().code());
    }

    // ---- the two paths agree -------------------------------------------------

    @Test
    void toHtml_shouldPrintEveryMark_thatValidateAccepts() {
        // Arrange -- what saves is what prints, or an author is checking their
        // work against a document that does not match the rules they were given
        String body = "<b>5a.</b> <i>Rate</i> is <u>$685.00</u>. Signed <rule w=\"25\">";

        // Act
        ClauseMarkup.validate(body);
        String html = ClauseMarkup.toHtml(body);

        // Assert
        assertFalse(html.contains("&lt;b&gt;"), html);
        assertFalse(html.contains("&lt;rule"), html);
        assertTrue(html.contains("<b>5a.</b>"), html);
        assertTrue(html.contains("class=\"rule\""), html);
    }

    // ---- blocks ----------------------------------------------------------------

    @Test
    void toHtml_shouldMakeBullets_fromLinesStartingWithADash() {
        assertEquals("<ul class=\"bullets\"><li>one</li><li><b>two</b></li></ul>",
                ClauseMarkup.toHtml("- one\n- <b>two</b>"));
    }

    @Test
    void toHtml_shouldKeepOneList_acrossBlankLinesBetweenItems() {
        assertEquals("<ol class=\"numbered\"><li>Grease.</li><li>Paint.</li></ol>",
                ClauseMarkup.toHtml("# Grease.\n\n# Paint."));
    }

    @Test
    void toHtml_shouldMakeATable_fromPipeRows() {
        assertEquals("<div class=\"seg\">Historical rents:</div>"
                        + "<table class=\"grid\"><tr><td>2025</td><td>2026</td></tr><tr><td>$690.00</td><td>$725.00</td></tr></table>",
                ClauseMarkup.toHtml("Historical rents:\n| 2025 | 2026 |\n| $690.00 | $725.00 |"));
    }

    @Test
    void toHtml_shouldEscapeInsideAListItem_likeAnywhereElse() {
        assertEquals("<ul class=\"bullets\"><li>&lt;script&gt;</li></ul>", ClauseMarkup.toHtml("- <script>"));
    }

    @Test
    void toHtml_shouldLeaveADashMidSentenceAlone() {
        assertEquals("Lot 4 - east side", ClauseMarkup.toHtml("Lot 4 - east side"));
    }

    @Test
    void validate_shouldRefuseATableWhoseRowsDoNotLineUp() {
        InvalidRequest e = assertThrows(InvalidRequest.class,
                () -> ClauseMarkup.validate("| a | b |\n| c |"));
        assertEquals("markup.table_ragged", e.details().get(0).code());
    }

    @Test
    void toHtml_shouldStillSeeAList_whenTheBodyHasBeenReIndented() {
        // Arrange -- what a SQL formatter does to a seed: every line after the
        // first gets pushed in, and the file's CRLF endings come along too
        String body = "- one\r\n  - two\r\n  - three\r\n";

        // Act
        String html = ClauseMarkup.toHtml(body);

        // Assert -- three items, and no stray carriage return inside them
        assertEquals("<ul class=\"bullets\"><li>one</li><li>two</li><li>three</li></ul>", html);
    }

    @Test
    void toHtml_shouldSeeAnIndentedNumberedItem() {
        assertEquals("<ol class=\"numbered\"><li>first</li><li>second</li></ol>",
                ClauseMarkup.toHtml("      # first\n      # second"));
    }

    @Test
    void hasBlocks_shouldNotBeFooledByIndentation() {
        assertTrue(ClauseMarkup.hasBlocks("  - one\r\n  - two"));
    }
}

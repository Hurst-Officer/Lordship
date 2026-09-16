package io.github.lordship.shared;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class TokenSyntaxTest {

    // ---- reading a body ------------------------------------------------------

    @Test
    void tokenNamesIn_shouldKeepTheNamespaceAsPartOfTheName() {
        assertEquals(Set.of("term.rate", "lot.lot_number"),
                TokenSyntax.tokenNamesIn("Lot {{lot.lot_number}} rents for {{term.rate}}."));
    }

    @Test
    void tokenNamesIn_shouldToleratePaddingInsideTheBraces() {
        assertEquals(Set.of("term.rate"), TokenSyntax.tokenNamesIn("{{  term.rate  }}"));
    }

    @Test
    void tokenNamesIn_shouldIgnoreSomethingThatIsNotAToken() {
        // No dot, so not a namespaced token name
        assertTrue(TokenSyntax.tokenNamesIn("{{rate}} and {{ }}").isEmpty());
    }

    @Test
    void repeatedListsIn_shouldNameEachBlockSeparately() {
        assertEquals(List.of("term.rent_schedule", "lot.vehicles"),
                TokenSyntax.repeatedListsIn(
                        "{{#each term.rent_schedule}}a{{/each}} {{#each lot.vehicles}}b{{/each}}"));
    }

    // ---- misplaced row tokens ------------------------------------------------

    @Test
    void misplacedRowTokens_shouldAcceptARowTokenInsideItsOwnBlock() {
        String body = "{{#each term.rent_schedule}}{{rent_step.period}} {{rent_step.rate}}{{/each}}";

        assertTrue(TokenSyntax.misplacedRowTokens(body).isEmpty());
    }

    @Test
    void misplacedRowTokens_shouldRejectARowTokenOutsideAnyBlock() {
        // The paste-from-Word case, and the deleted-marker case
        String body = "The rent is {{rent_step.rate}} per month.";

        assertEquals(List.of("rent_step.rate"), TokenSyntax.misplacedRowTokens(body));
    }

    @Test
    void misplacedRowTokens_shouldRejectARowTokenInsideTheWrongBlock() {
        // A rate belongs to a rent step, not to a vehicle
        String body = "{{#each lot.vehicles}}{{rent_step.rate}}{{/each}}";

        assertEquals(List.of("rent_step.rate"), TokenSyntax.misplacedRowTokens(body));
    }

    @Test
    void misplacedRowTokens_shouldAllowADocumentTokenInsideABlock() {
        // A repeated line may still name something document-wide
        String body = "{{#each term.rent_schedule}}{{property.community_name}}: {{rent_step.rate}}{{/each}}";

        assertTrue(TokenSyntax.misplacedRowTokens(body).isEmpty());
    }

    @Test
    void misplacedRowTokens_shouldCatchOneStrandedTokenBesideACorrectBlock() {
        // The subtle one: the block is fine, the trailing reference is not
        String body = "{{#each term.rent_schedule}}{{rent_step.rate}}{{/each}} "
                + "and thereafter {{rent_step.rate}}.";

        assertEquals(List.of("rent_step.rate"), TokenSyntax.misplacedRowTokens(body));
    }

    @Test
    void misplacedRowTokens_shouldReportEachOffenderOnce() {
        String body = "{{rent_step.rate}} ... {{rent_step.rate}} ... {{rent_step.period}}";

        assertEquals(List.of("rent_step.rate", "rent_step.period"),
                TokenSyntax.misplacedRowTokens(body));
    }

    @Test
    void misplacedRowTokens_shouldBeEmptyForAnOrdinaryClause() {
        // Costs nothing to run on the clauses that have no repeats
        assertTrue(TokenSyntax.misplacedRowTokens(
                "Tenant shall pay {{term.rate}} on the {{term.payment_due_day}}.").isEmpty());
    }

    @Test
    void misplacedRowTokens_shouldIgnoreAnUnknownToken() {
        // Unknown tokens are already refused by their own check; this one has
        // no opinion about them
        assertTrue(TokenSyntax.misplacedRowTokens("{{made.up_token}}").isEmpty());
    }

    @Test
    void misplacedRowTokens_shouldHandleANullBody() {
        assertTrue(TokenSyntax.misplacedRowTokens(null).isEmpty());
    }
}
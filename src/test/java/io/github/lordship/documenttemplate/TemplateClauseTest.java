package io.github.lordship.documenttemplate;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure domain logic -- no Spring, no database. This is where the conditional
 * clause mechanism actually lives, so it is where it is cheapest to test.
 */
public class TemplateClauseTest {

    private TemplateClause clause(String body, String conditionField, List<String> conditionValues) {
        return new TemplateClause(
                UUID.randomUUID(),
                BigDecimal.valueOf(1000),
                "TEST_CLAUSE",
                null,
                body,
                conditionField,
                conditionValues,
                false,
                null,
                null,
                null,
                null,
                null, null, true, null, null);
    }

    // ---- appliesTo -----------------------------------------------------------

    @Test
    void appliesTo_shouldAlwaysPrint_whenTheClauseIsUnconditional() {
        // Arrange
        TemplateClause clause = clause("Rent is {{term.rate}}.", null, List.of());

        // Act & Assert: the argument is ignored entirely
        assertTrue(clause.appliesTo("FLAT"));
        assertTrue(clause.appliesTo(null));
    }

    @Test
    void appliesTo_shouldPrint_onlyForItsOwnMethodValues() {
        // Arrange
        TemplateClause clause = clause(
                "A returned payment incurs {{term.nsf_fee_amount}}.",
                "term.nsf_fee_method",
                List.of("FLAT"));

        // Act & Assert
        assertTrue(clause.appliesTo("FLAT"));
        assertFalse(clause.appliesTo("BANK_OR_FLAT"));
        assertFalse(clause.appliesTo("NONE"));
    }

    // The bug this test exists for: List.of(...) throws on a null probe rather
    // than answering false, so previewing a half-specified deal blew up instead
    // of showing the holes.
    @Test
    void appliesTo_shouldNotPrint_whenTheMethodWasNeverSupplied() {
        // Arrange
        TemplateClause clause = clause(
                "A returned payment incurs {{term.nsf_fee_amount}}.",
                "term.nsf_fee_method",
                List.of("FLAT"));

        // Act & Assert
        assertDoesNotThrow(() -> clause.appliesTo(null));
        assertFalse(clause.appliesTo(null));
    }

    @Test
    void appliesTo_shouldPrint_forAnyOfSeveralValues() {
        // Arrange
        TemplateClause clause = clause(
                "A returned payment incurs {{term.nsf_fee_amount}}.",
                "term.nsf_fee_method",
                List.of("FLAT", "BANK_OR_FLAT"));

        // Act & Assert
        assertTrue(clause.appliesTo("FLAT"));
        assertTrue(clause.appliesTo("BANK_OR_FLAT"));
        assertFalse(clause.appliesTo("NONE"));
    }

    @Test
    void conditionValues_shouldNeverBeNull() {
        // Arrange & Act
        TemplateClause clause = clause("body", null, null);

        // Assert
        assertNotNull(clause.conditionValues());
        assertTrue(clause.conditionValues().isEmpty());
        assertFalse(clause.isConditional());
    }

    // ---- tokenNames ----------------------------------------------------------

    @Test
    void tokenNames_shouldFindNamespacedTokens_inOrder() {
        // Arrange
        TemplateClause clause = clause(
                "Rent of {{term.rate}} is due at {{property.remittance_address}}.", null, List.of());

        // Act
        Set<String> found = clause.tokenNames();

        // Assert
        assertEquals(2, found.size());
        assertTrue(found.contains("term.rate"));
        assertTrue(found.contains("property.remittance_address"));
    }

    // A bare {{rate}} is not a token: every real name carries its namespace, and
    // accepting the short form would let an unresolvable token through.
    @Test
    void tokenNames_shouldIgnoreATokenWithNoNamespace() {
        // Arrange
        TemplateClause clause = clause("Rent is {{rate}}.", null, List.of());

        // Act & Assert
        assertTrue(clause.tokenNames().isEmpty());
    }

    @Test
    void tokenNames_shouldReturnEmpty_whenTheBodyIsNull() {
        assertTrue(clause(null, null, List.of()).tokenNames().isEmpty());
    }

    @Test
    void tokenNames_shouldNotRepeatATokenUsedTwice() {
        // Arrange
        TemplateClause clause = clause("{{term.rate}} and again {{term.rate}}.", null, List.of());

        // Act & Assert
        assertEquals(1, clause.tokenNames().size());
    }

    // ---- unguardedTokens -----------------------------------------------------

    // The $1.50 lease: late_fee_amount shares its column with late_fee_percent,
    // so an unconditioned clause renders a percentage as money.
    @Test
    void unguardedTokens_shouldFlagAnAmountWithNoCondition() {
        // Arrange
        TemplateClause clause = clause(
                "A late fee of {{term.late_fee_amount}} applies.", null, List.of());

        // Act
        List<String> unguarded = clause.unguardedTokens();

        // Assert
        assertEquals(List.of("term.late_fee_amount"), unguarded);
    }

    @Test
    void unguardedTokens_shouldBeEmpty_whenTheClauseGuardsTheAmount() {
        // Arrange
        TemplateClause clause = clause(
                "A late fee of {{term.late_fee_amount}} applies.",
                "term.late_fee_method",
                List.of("FLAT"));

        // Act & Assert
        assertTrue(clause.unguardedTokens().isEmpty());
    }

    // Conditioning on a value that does not populate the amount is not a guard:
    // a PERCENT_OF_RENT tenant would still be shown a money figure.
    @Test
    void unguardedTokens_shouldFlag_whenAConditionValueDoesNotPopulateTheAmount() {
        // Arrange
        TemplateClause clause = clause(
                "A late fee of {{term.late_fee_amount}} applies.",
                "term.late_fee_method",
                List.of("FLAT", "PERCENT_OF_RENT"));

        // Act & Assert
        assertEquals(List.of("term.late_fee_amount"), clause.unguardedTokens());
    }

    @Test
    void unguardedTokens_shouldFlag_whenTheConditionIsOnADifferentMethod() {
        // Arrange
        TemplateClause clause = clause(
                "A late fee of {{term.late_fee_amount}} applies.",
                "term.nsf_fee_method",
                List.of("FLAT"));

        // Act & Assert
        assertEquals(List.of("term.late_fee_amount"), clause.unguardedTokens());
    }

    @Test
    void unguardedTokens_shouldIgnoreTokensThatDependOnNoMethod() {
        // Arrange
        TemplateClause clause = clause(
                "Rent of {{term.rate}} for lot {{lot.lot_number}}.", null, List.of());

        // Act & Assert
        assertTrue(clause.unguardedTokens().isEmpty());
    }

    @Test
    void unguardedTokens_shouldIgnoreAnUnknownToken() {
        // Arrange: an unknown token is the save-time validator's problem, and
        // reporting it here too would say the same thing twice
        TemplateClause clause = clause("A fee of {{term.raet}}.", null, List.of());

        // Act & Assert
        assertTrue(clause.unguardedTokens().isEmpty());
    }
}

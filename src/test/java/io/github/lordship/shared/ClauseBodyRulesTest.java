package io.github.lordship.shared;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules a clause body answers to, wherever it was written.
 *
 * <p>These lived inside {@code DocumentTemplateService} until a park, and then
 * an office worker, could write clause bodies too. The point of the tests is
 * that one set of rules governs all three tables -- anybody writing lease
 * wording is writing lease wording.
 */
public class ClauseBodyRulesTest {

    // ---- tokens in the body --------------------------------------------------

    @Test
    void validateBody_shouldAcceptAKnownToken() {
        // Arrange
        String body = "Tenant shall pay {{term.rate}} on the {{term.payment_due_day}}.";

        // Act + Assert
        assertDoesNotThrow(() -> ClauseBodyRules.validateBody(body));
    }

    @Test
    void validateBody_shouldAcceptAnEmptyBody() {
        // Arrange -- "add clause" is a button, so a clause starts with no words
        // Act + Assert
        assertDoesNotThrow(() -> ClauseBodyRules.validateBody((String) null));
    }

    @Test
    void validateBody_shouldRejectATokenThatDoesNotExist() {
        // Arrange
        String body = "Rent is {{term.montly_rate}}.";

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class,
                () -> ClauseBodyRules.validateBody(body));

        // Assert -- caught at save, because at generate it is a hole in a lease
        assertEquals("clause.body_has_unknown_tokens", thrown.problem().code());
        assertEquals(1, thrown.details().size());
    }

    @Test
    void validateBody_shouldSuggestTheNearestToken_whenTheNameIsClose() {
        // Arrange -- one transposition away from term.rate
        String body = "Rent is {{term.raet}}.";

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class,
                () -> ClauseBodyRules.validateBody(body));

        // Assert -- the person on the other end is writing a lease clause, and a
        // bare rejection tells them nothing
        DomainProblem.Problem detail = thrown.details().get(0);
        assertEquals("token.unknown_did_you_mean", detail.code());
        assertEquals("{{term.raet}}", detail.args().get(0));
        assertEquals("{{term.rate}}", detail.args().get(1));
    }

    @Test
    void validateBody_shouldReportEveryUnknownTokenAtOnce() {
        // Arrange -- an author who pasted a half-remembered block should not fix
        // one token per round trip
        String body = "{{term.nonsense_one}} and {{term.nonsense_two}}.";

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class,
                () -> ClauseBodyRules.validateBody(body));

        // Assert
        assertEquals(2, thrown.details().size());
    }

    // ---- row tokens ----------------------------------------------------------

    @Test
    void validateBody_shouldAcceptARowToken_insideARepeatOverItsOwnList() {
        // Arrange
        String body = "{{#each term.rent_schedule}}{{rent_step.period}}: "
                + "{{rent_step.rate}}{{/each}}";

        // Act + Assert
        assertDoesNotThrow(() -> ClauseBodyRules.validateBody(body));
    }

    @Test
    void validateBody_shouldRejectARowToken_writtenOutsideAnyRepeatBlock() {
        // Arrange -- "the rate of which step?" has no answer here
        String body = "The rent is {{rent_step.rate}}.";

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class,
                () -> ClauseBodyRules.validateBody(body));

        // Assert -- an error rather than a warning: left alone it prints as a
        // stranded {{rent_step.rate}} on a lease
        assertEquals("clause.body_has_misplaced_row_tokens", thrown.problem().code());
        DomainProblem.Problem detail = thrown.details().get(0);
        assertEquals("token.row_token_misplaced", detail.code());
        assertEquals("{{rent_step.rate}}", detail.args().get(0));
        assertEquals("term.rent_schedule", detail.args().get(1));
    }

    @Test
    void validateBody_shouldReportUnknownTokensBeforeMisplacedOnes() {
        // Arrange -- both faults in one body
        String body = "{{term.nonsense}} and {{rent_step.rate}}.";

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class,
                () -> ClauseBodyRules.validateBody(body));

        // Assert -- a name the registry does not carry has no list to belong to
        // either, so reporting it twice would say the same thing in two voices
        assertEquals("clause.body_has_unknown_tokens", thrown.problem().code());
    }

    // ---- conditions ----------------------------------------------------------

    @Test
    void validateCondition_shouldAcceptAMethodAndAValueItTakes() {
        // Arrange
        Map<String, Object> changes = Map.of(
                "condition_field", "term.water_method",
                "condition_values", List.of("FLAT"));

        // Act + Assert
        assertDoesNotThrow(() ->
                ClauseBodyRules.validateCondition(null, List.of(), changes));
    }

    @Test
    void validateCondition_shouldAcceptAnUnconditionalClause() {
        // Arrange -- which is most clauses
        // Act + Assert
        assertDoesNotThrow(() ->
                ClauseBodyRules.validateCondition(null, List.of(), Map.of("body", "Words.")));
    }

    @Test
    void validateCondition_shouldRejectValuesWithNoFieldToTestThemAgainst() {
        // Arrange
        Map<String, Object> changes = Map.of("condition_values", List.of("FLAT"));

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class,
                () -> ClauseBodyRules.validateCondition(null, List.of(), changes));

        // Assert
        assertEquals("clause.values_without_field", thrown.problem().code());
        assertEquals("conditionField", thrown.problem().field());
    }

    @Test
    void validateCondition_shouldRejectAFieldWithNoValues() {
        // Arrange -- a clause that could never print
        Map<String, Object> changes = Map.of("condition_field", "term.water_method");

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class,
                () -> ClauseBodyRules.validateCondition(null, List.of(), changes));

        // Assert
        assertEquals("clause.field_without_values", thrown.problem().code());
        assertEquals("conditionValues", thrown.problem().field());
    }

    @Test
    void validateCondition_shouldRejectAFieldThatIsNotAMethod() {
        // Arrange -- "print this when the rate is 725" is not a rule anyone means
        Map<String, Object> changes = Map.of(
                "condition_field", "term.rate",
                "condition_values", List.of("725"));

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class,
                () -> ClauseBodyRules.validateCondition(null, List.of(), changes));

        // Assert
        assertEquals("clause.condition_not_a_method", thrown.problem().code());
        assertEquals("conditionField", thrown.problem().field());
    }

    @Test
    void validateCondition_shouldRejectAValueTheMethodDoesNotTake() {
        // Arrange -- a typo that would otherwise be a clause that silently never prints
        Map<String, Object> changes = Map.of(
                "condition_field", "term.water_method",
                "condition_values", List.of("FLTA"));

        // Act
        InvalidRequest thrown = assertThrows(InvalidRequest.class,
                () -> ClauseBodyRules.validateCondition(null, List.of(), changes));

        // Assert
        assertEquals("token.value_not_allowed", thrown.problem().code());
        assertEquals("conditionValues", thrown.problem().field());
    }

    @Test
    void validateCondition_shouldReadTheUntouchedHalf_fromTheCurrentState() {
        // Arrange -- the request sets only the values; the field is already stored
        Map<String, Object> changes = Map.of("condition_values", List.of("RUBS"));

        // Act + Assert -- valid because the stored field is a method that takes RUBS
        assertDoesNotThrow(() -> ClauseBodyRules.validateCondition(
                "term.water_method", List.of("FLAT"), changes));
    }

    // ---- clearing a condition ------------------------------------------------

    @Test
    void withConditionClearing_shouldClearBothHalves_whenTheFieldIsCleared() {
        // Arrange
        Map<String, Object> changes = new java.util.HashMap<>();
        changes.put("condition_field", null);

        // Act
        Map<String, Object> effective = ClauseBodyRules.withConditionClearing(changes);

        // Assert -- the two columns are stored together, so clearing one clears
        // the other rather than being refused for leaving the pair half-set
        assertNull(effective.get("condition_field"));
        assertEquals(List.of(), effective.get("condition_values"));
    }

    @Test
    void withConditionClearing_shouldClearBothHalves_whenTheLastValueIsUnticked() {
        // Arrange -- an editor unticking the last checkbox should not have to
        // know it must also null the field
        Map<String, Object> changes = new java.util.HashMap<>();
        changes.put("condition_values", List.of());

        // Act
        Map<String, Object> effective = ClauseBodyRules.withConditionClearing(changes);

        // Assert
        assertNull(effective.get("condition_field"));
        assertEquals(List.of(), effective.get("condition_values"));
    }

    @Test
    void withConditionClearing_shouldLeaveARealConditionAlone() {
        // Arrange
        Map<String, Object> changes = Map.of(
                "condition_field", "term.water_method",
                "condition_values", List.of("FLAT"));

        // Act
        Map<String, Object> effective = ClauseBodyRules.withConditionClearing(changes);

        // Assert
        assertEquals("term.water_method", effective.get("condition_field"));
        assertEquals(List.of("FLAT"), effective.get("condition_values"));
    }
}
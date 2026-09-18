package io.github.lordship.shared;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What an author may write in a clause body, and how a refusal is worded.
 *
 * <p>Three modules write clause bodies now: an admin authoring a template, a
 * park adding a rule of its own, and an office worker typing a sentence onto
 * one agreement. All three end up on a real lease, so all three answer the
 * same questions -- and a second copy of these rules is somebody able to save
 * {@code {{term.raet}}} and find out at generate, or never.
 *
 * <p>Lives here rather than in {@code documenttemplate} because it is coupled
 * to nothing there: it reads {@link TokenSyntax} for the parse and
 * {@link DocumentToken} for the registry, and both are already here. It is the
 * third member of that group, which is the one worth pulling into a
 * {@code shared/documents/} sub-package when this package gets split.
 *
 * <p>The parsing itself lives in {@link TokenSyntax}, which the renderer also
 * uses. This class is the policy on top of it: which tokens exist, which are
 * in the wrong place, and how to say so in a form a translation can match.
 */
public final class ClauseBodyRules {

    private ClauseBodyRules() {}

    /**
     * Every {@code {{token}}} in a body has to be one the renderer can resolve,
     * and every row token has to sit where it can resolve. Caught at save
     * rather than at generate, and answered with a suggestion, because the
     * person on the other end is writing a lease clause and a bare rejection
     * tells them nothing.
     *
     * <p>Two failures, reported separately because they have different fixes.
     * An unknown token is a typo or a token that does not exist; a misplaced
     * row token exists and is spelled right but was written where it has
     * nothing to be the rate OF. Unknown tokens are reported first: a name the
     * registry does not carry has no list to belong to either, so reporting it
     * twice would say the same thing in two voices.
     *
     * <p>A null body is a clause not written yet, which is allowed -- "add
     * clause" is a button, not a form.
     */
    public static void validateBody(String body) {
        if (body == null) {
            return;
        }

        List<DomainProblem.Problem> unknown = new ArrayList<>();
        for (String name : TokenSyntax.tokenNamesIn(body)) {
            if (DocumentToken.of(name).isEmpty()) {
                unknown.add(unknownTokenProblem(name));
            }
        }
        if (!unknown.isEmpty()) {
            throw InvalidRequest.withDetails("clause.body_has_unknown_tokens", unknown);
        }

        // A row token outside its repeat block, or inside a repeat over some
        // other list, has no row to read from. Left to generate it would print
        // as a stranded {{rent_step.rate}} on a lease, which is why this is an
        // error rather than the warning unguardedTokens gives.
        List<DomainProblem.Problem> misplaced = new ArrayList<>();
        for (String name : TokenSyntax.misplacedRowTokens(body)) {
            misplaced.add(misplacedRowTokenProblem(name));
        }
        if (!misplaced.isEmpty()) {
            throw InvalidRequest.withDetails("clause.body_has_misplaced_row_tokens", misplaced);
        }

        // Marks last. A body with a typo'd token and an unclosed <b> has two
        // faults, and reporting the one the author is more likely to have meant
        // first keeps a single retype from turning into two.
        ClauseMarkup.validate(body);
    }

    /** The same check where the body arrives inside a patch map. */
    public static void validateBody(Map<String, Object> changes) {
        if (!changes.containsKey("body")) {
            return;
        }
        Object raw = changes.get("body");
        validateBody(raw == null ? null : String.valueOf(raw));
    }

    /**
     * Unchecking the last value and clearing the field are the same act: the
     * clause is no longer conditional. The two columns are stored together, so
     * clearing either one clears the other, rather than being refused for
     * leaving the pair half-set -- an editor that unticks the last checkbox
     * should not have to know it must also null the field.
     *
     * <p>Only when the other half is not being set in the same request. Sending
     * a field with an empty list, or values with a null field, is still a
     * mistake worth reporting: it asks for a clause that could never print.
     */
    public static Map<String, Object> withConditionClearing(Map<String, Object> changes) {
        boolean fieldSet = changes.containsKey("condition_field")
                && asStringOrNull(changes.get("condition_field")) != null;
        boolean valuesSet = changes.containsKey("condition_values")
                && !asStringList(changes.get("condition_values")).isEmpty();

        boolean fieldCleared = changes.containsKey("condition_field") && !fieldSet;
        boolean valuesCleared = changes.containsKey("condition_values") && !valuesSet;

        if (!(fieldCleared && !valuesSet) && !(valuesCleared && !fieldSet)) {
            return changes;
        }

        Map<String, Object> effective = new LinkedHashMap<>(changes);
        effective.put("condition_field", null);
        effective.put("condition_values", List.of());
        return effective;
    }

    /**
     * A clause may only branch on a method, and only on values that column
     * permits. Conditioning on {@code term.rate} is a category error --
     * "print this when the rate is 725" is not a rule anyone means -- and
     * {@code BANK_OR_FLTA} is a typo that would otherwise show up as a clause
     * that silently never prints.
     *
     * <p>Both halves move together: a field with no values matches nothing, and
     * values with no field have nothing to test.
     *
     * <p>Takes the current pair rather than a row, because a park's clause and
     * a template's clause live in different tables and answer to the same rule.
     */
    public static void validateCondition(String currentField,
                                         List<String> currentValues,
                                         Map<String, Object> changes) {
        boolean touched = changes.containsKey("condition_field") || changes.containsKey("condition_values");
        if (!touched) {
            return;
        }

        String field = changes.containsKey("condition_field")
                ? asStringOrNull(changes.get("condition_field"))
                : currentField;

        List<String> values = changes.containsKey("condition_values")
                ? asStringList(changes.get("condition_values"))
                : currentValues;

        if (field == null && values.isEmpty()) {
            return; // unconditional, which is most clauses
        }
        if (field == null) {
            throw InvalidRequest.onField("conditionField", "clause.values_without_field");
        }
        if (values.isEmpty()) {
            throw InvalidRequest.onField("conditionValues", "clause.field_without_values", field);
        }

        DocumentToken token = DocumentToken.of(field).orElseThrow(() -> unknownToken(field));

        if (!token.canCondition()) {
            throw InvalidRequest.onField("conditionField", "clause.condition_not_a_method",
                    placeholder(field), token.format(), conditionableTokens());
        }

        Set<String> allowed = token.allowedValues();
        List<String> unknown = values.stream().filter(v -> !allowed.contains(v)).toList();
        if (!unknown.isEmpty()) {
            throw InvalidRequest.onField("conditionValues", "token.value_not_allowed",
                    placeholder(field), String.join(", ", unknown), sorted(allowed));
        }
    }

    public static String conditionableTokens() {
        return java.util.Arrays.stream(DocumentToken.values())
                .filter(DocumentToken::canCondition)
                .map(DocumentToken::placeholder)
                .sorted()
                .collect(Collectors.joining(", "));
    }

    public static String asStringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public static List<String> asStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .toList();
        }
        throw InvalidRequest.onField("conditionValues", "clause.condition_values_not_a_list");
    }
    /**
     * The nearest real token, or empty -- a wrong suggestion is worse than none.
     *
     * <p>Returns the NAME rather than a ready-made "did you mean" phrase. The
     * phrase is English, and English belongs in messages.properties; this
     * returns the fact the phrase is built from.
     */
    public static Optional<String> suggestionFor(String unknown) {
        return DocumentToken.tokenNames().stream()
                .map(known -> Map.entry(known, editDistance(unknown, known)))
                .filter(e -> e.getValue() <= 3)
                .min(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey);
    }

    /**
     * An unknown token, with a suggestion when there is a near miss.
     *
     * <p>Two codes rather than one with an optional tail, because a message
     * with a dangling "-- did you mean ?" is worse than two lines in a
     * properties file.
     */
    public static InvalidRequest unknownToken(String name) {
        return suggestionFor(name)
                .map(hint -> InvalidRequest.of("token.unknown_did_you_mean",
                        placeholder(name), placeholder(hint)))
                .orElseGet(() -> InvalidRequest.of("token.unknown", placeholder(name)));
    }

    /** The same, as a detail inside a collected failure. */
    public static DomainProblem.Problem unknownTokenProblem(String name) {
        return suggestionFor(name)
                .map(hint -> DomainProblem.Problem.of("token.unknown_did_you_mean",
                        placeholder(name), placeholder(hint)))
                .orElseGet(() -> DomainProblem.Problem.of("token.unknown", placeholder(name)));
    }

    /**
     * A row token written where it cannot resolve, and the list it does belong
     * to -- so the message can say "move it inside" rather than "something is
     * wrong".
     */
    public static DomainProblem.Problem misplacedRowTokenProblem(String name) {
        String list = DocumentToken.of(name)
                .flatMap(DocumentToken::repeatsOver)
                .map(DocumentToken::token)
                .orElse(null);
        return DomainProblem.Problem.of("token.row_token_misplaced", placeholder(name), list);
    }

    /** How a token is written in a body, so a message can show what the author typed. */
    public static String placeholder(String name) {
        return "{{" + name + "}}";
    }

    public static String sorted(Set<String> values) {
        return values.stream().sorted().collect(Collectors.joining(", "));
    }

    public static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitute = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitute, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}

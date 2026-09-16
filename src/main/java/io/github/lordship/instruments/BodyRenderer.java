package io.github.lordship.instruments;

import io.github.lordship.shared.TokenSyntax;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an authored clause body into the words on the page.
 *
 * <p>Pure: hand it a body and a {@link TokenValues} and it substitutes. It
 * reads nothing, fetches nothing, and knows nothing about which tokens exist --
 * {@code DocumentToken} is the vocabulary a clause is validated against when it
 * is SAVED, and by the time a body reaches here that question is settled.
 *
 * <p>Two constructs. A scalar token, {@code {{term.rate}}}, is replaced by its
 * value. A repeat block,
 *
 * <pre>{@code {{#each term.rent_schedule}}...{{/each}}}</pre>
 *
 * renders its inner body once per row of that list. Inside the block, row
 * tokens resolve against the row and everything else still resolves against the
 * document, so a repeated line may name the community as well as the rate.
 *
 * <p>Repeat blocks exist because a document says things a single value cannot:
 * five dated rents, four vehicles, three tenants who sign. The alternative --
 * one token per position, {@code rate_1} through {@code rate_5} -- prints empty
 * rows on a shorter schedule, which on a signed lease is worse than wrong.
 *
 * <p>Nothing is silently blank. A token with no value is left standing in the
 * text AND reported in {@link Rendered#unresolved()}, so a preview shows the
 * holes while generation can refuse. A lease that quietly omits its rent is the
 * one failure mode worth being loud about.
 */
public final class BodyRenderer {

    // The patterns live in TokenSyntax, not here. What an author may write is
    // one rule, and a renderer that read it differently from the save-time
    // validator would let a clause save clean and print wrong.
    private static final Pattern TOKEN = TokenSyntax.TOKEN;
    private static final Pattern REPEAT = TokenSyntax.REPEAT;

    private BodyRenderer() {}

    /**
     * The finished text, plus every token that had nothing to put there.
     *
     * <p>Unresolved names are deduplicated and in the order met, so the message
     * an office worker sees names each missing figure once.
     */
    public record Rendered(String text, List<String> unresolved) {

        public Rendered {
            unresolved = List.copyOf(unresolved);
        }

        public boolean isComplete() {
            return unresolved.isEmpty();
        }
    }

    public static Rendered render(String bodyTemplate, TokenValues values) {
        if (bodyTemplate == null || bodyTemplate.isEmpty()) {
            return new Rendered("", List.of());
        }

        Set<String> unresolved = new LinkedHashSet<>();
        String expanded = expandRepeats(bodyTemplate, values, unresolved);
        String text = substitute(expanded, values, null, unresolved);

        return new Rendered(text, List.copyOf(unresolved));
    }

    /**
     * Replaces each repeat block with its rows rendered back to back.
     *
     * <p>Runs before scalar substitution so row tokens are consumed against
     * their row rather than looked up as document-wide values and reported
     * missing.
     *
     * <p>A list that was never supplied renders as nothing and is reported. An
     * empty list renders as nothing and is NOT: a lease with no vehicles listed
     * has correctly said so, and a clause is expected to carry its own wording
     * for that case rather than relying on a blank.
     */
    private static String expandRepeats(String body, TokenValues values, Set<String> unresolved) {
        Matcher block = REPEAT.matcher(body);
        StringBuilder out = new StringBuilder();

        while (block.find()) {
            String listToken = block.group(1);
            String rowTemplate = block.group(2);
            List<Map<String, String>> rows = values.list(listToken);

            if (rows == null) {
                unresolved.add(listToken);
                block.appendReplacement(out, "");
                continue;
            }

            StringBuilder rendered = new StringBuilder();
            for (Map<String, String> row : rows) {
                rendered.append(substitute(rowTemplate, values, row, unresolved));
            }
            block.appendReplacement(out, Matcher.quoteReplacement(rendered.toString()));
        }
        block.appendTail(out);
        return out.toString();
    }

    /**
     * Substitutes every scalar token in one stretch of text.
     *
     * <p>{@code row} is the current row inside a repeat block, or null outside
     * one. A row wins over the document, so a row token shadows a document
     * value of the same name rather than the other way round -- the inner scope
     * is the more specific statement.
     */
    private static String substitute(String text,
                                     TokenValues values,
                                     Map<String, String> row,
                                     Set<String> unresolved) {
        Matcher token = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();

        while (token.find()) {
            String name = token.group(1);
            String value = (row != null && row.containsKey(name))
                    ? row.get(name)
                    : values.scalar(name);

            if (value == null) {
                // Left standing on purpose: a visible {{term.rate}} on a preview
                // is caught by eye, a silent gap is not.
                unresolved.add(name);
                token.appendReplacement(out, Matcher.quoteReplacement(token.group(0)));
            } else {
                token.appendReplacement(out, Matcher.quoteReplacement(value));
            }
        }
        token.appendTail(out);
        return out.toString();
    }

    /** The list tokens a body repeats over, in the order they appear. */
    public static List<String> repeatedLists(String bodyTemplate) {
        return TokenSyntax.repeatedListsIn(bodyTemplate);
    }
}
package io.github.lordship.shared;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an author may write in a clause body, and how to read it back.
 *
 * <p>One place, because three modules ask the same questions of the same text
 * and must not answer them differently: {@code documenttemplate} validating a
 * clause at save, {@code instruments} substituting at generate, and the clause
 * editor's token picker. A regex that drifts between the validator and the
 * renderer is a clause that saves clean and prints wrong.
 *
 * <p>Two constructs. A token, {@code {{term.rate}}}, stands for one value. A
 * repeat block,
 *
 * <pre>{@code {{#each term.rent_schedule}}...{{/each}}}</pre>
 *
 * renders its contents once per row of that list, and the row tokens inside it
 * mean nothing anywhere else.
 */
public final class TokenSyntax {

    /**
     * A token reference. Names are namespaced -- {@code term.rate},
     * {@code lot.lot_number} -- so the dot belongs to the name rather than
     * being a separator to split on.
     */
    public static final Pattern TOKEN =
            Pattern.compile("\\{\\{\\s*([a-z0-9_]+(?:\\.[a-z0-9_]+)+)\\s*}}");

    /**
     * A repeat block and its contents. DOTALL because a repeated row spans
     * lines; the quantifier is reluctant so two blocks in one body stay two
     * blocks rather than merging into everything between the first opener and
     * the last closer.
     */
    public static final Pattern REPEAT = Pattern.compile(
            "\\{\\{#each\\s+([a-z0-9_]+(?:\\.[a-z0-9_]+)+)\\s*}}(.*?)\\{\\{/each}}",
            Pattern.DOTALL);

    private TokenSyntax() {}

    /** Every token named in this body, without braces, in the order they appear. */
    public static Set<String> tokenNamesIn(String body) {
        Set<String> found = new LinkedHashSet<>();
        if (body == null) {
            return found;
        }
        Matcher matcher = TOKEN.matcher(body);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /** The lists this body repeats over, in the order they appear. */
    public static List<String> repeatedListsIn(String body) {
        List<String> found = new ArrayList<>();
        if (body == null) {
            return List.of();
        }
        Matcher block = REPEAT.matcher(body);
        while (block.find()) {
            found.add(block.group(1));
        }
        return List.copyOf(found);
    }

    /**
     * Row tokens written where they cannot resolve, in the order they appear.
     *
     * <p>{@code rent_step.rate} answers "the rate of which step?", so it means
     * something inside a repeat over {@code term.rent_schedule} and nothing at
     * all outside one -- or inside a repeat over some other list. Caught at
     * save, because the alternative is discovering it as a stranded
     * {@code {{rent_step.rate}}} on a lease.
     *
     * <p>Empty for every ordinary body, so the check costs nothing to run on
     * clauses that have no repeats in them.
     */
    public static List<String> misplacedRowTokens(String body) {
        if (body == null) {
            return List.of();
        }

        List<String> misplaced = new ArrayList<>();
        StringBuilder outside = new StringBuilder();
        Matcher block = REPEAT.matcher(body);
        int cursor = 0;

        while (block.find()) {
            outside.append(body, cursor, block.start());
            cursor = block.end();
            collectMisplaced(block.group(2), block.group(1), misplaced);
        }
        outside.append(body.substring(cursor));

        // Anything left is outside every block, so no row token belongs to it.
        collectMisplaced(outside.toString(), null, misplaced);
        return List.copyOf(misplaced);
    }

    /**
     * @param belongsTo the list this stretch of text repeats over, or null when
     *                  it is not inside a block at all
     */
    private static void collectMisplaced(String text, String belongsTo, List<String> misplaced) {
        for (String name : tokenNamesIn(text)) {
            String required = DocumentToken.of(name)
                    .flatMap(DocumentToken::repeatsOver)
                    .map(DocumentToken::token)
                    .orElse(null);

            if (required != null && !required.equals(belongsTo) && !misplaced.contains(name)) {
                misplaced.add(name);
            }
        }
    }
}
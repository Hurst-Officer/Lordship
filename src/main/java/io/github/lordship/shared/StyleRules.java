package io.github.lordship.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What an admin may write in a {@code document_style}: CSS declarations and
 * nothing else, e.g. {@code font-weight: bold; font-size: 12pt; border: 2px solid #000;}.
 *
 * <p>One check for both callers, like {@link ClauseMarkup}: the style editor
 * refuses at save, the renderer drops a style that fails rather than print it.
 *
 * <p>No braces, so a style cannot close its own rule and start another. No
 * {@code <}, so it cannot close the style tag. No {@code @} or {@code url(},
 * so it cannot fetch anything -- a document whose look depends on a file it
 * fetches renders differently in five years. No backslash, since CSS escapes
 * can spell any of the above.
 */
public final class StyleRules {

    // sql: document_style.css
    private static final List<String> REFUSED = List.of("{", "}", "<", "@", "url(", "\\", "expression(");

    private StyleRules() {}

    /** The refused pieces this css contains, in list order; empty when it is fine. */
    public static List<String> problemsIn(String css) {
        List<String> found = new ArrayList<>();
        if (css == null) {
            return found;
        }
        String lower = css.toLowerCase(Locale.ROOT);
        for (String piece : REFUSED) {
            if (lower.contains(piece)) {
                found.add(piece);
            }
        }
        return List.copyOf(found);
    }

    public static boolean isSafe(String css) {
        return problemsIn(css).isEmpty();
    }
}

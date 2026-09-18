package io.github.lordship.shared;

import java.util.Arrays;
import java.util.Optional;

/**
 * The parts of every page an admin may restyle for one document -- his edit to
 * the built-in look. A style with a target applies everywhere that part
 * appears; a style without one is a named style a clause or section picks.
 *
 * <p>These are places on the page, not looks, so the list is fixed by what the
 * renderer draws rather than by taste. What they look like is the admin's css.
 */
public enum StyleTarget {
    // sql: document_style.target
    PAGE,           // margins and paper size
    BODY,           // the whole document's type
    SECTION_TITLE,  // "RULES AND REGULATIONS"
    CLAUSE_TITLE,   // "Water"
    NUMBER,         // "9."  "A."
    STATUTE;        // the small italic citation under a clause

    public static Optional<StyleTarget> of(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(target -> target.name().equals(name)).findFirst();
    }
}

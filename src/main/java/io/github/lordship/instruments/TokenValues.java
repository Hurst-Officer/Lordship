package io.github.lordship.instruments;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one document has to say, already formatted as it will print.
 *
 * <p>The seam between fetching and rendering. Whoever builds this has done all
 * the reading -- charge term, instrument, lot, property, global settings -- and
 * all the formatting, so {@link BodyRenderer} does nothing but substitute. That
 * split is what lets the renderer be tested against a handful of strings
 * instead of a database.
 *
 * <p>Two kinds of value, because a document says two kinds of thing. A scalar
 * is one figure: the rate, the lot number, the landlord's address. A list is a
 * run of rows the clause repeats over: the steps of a rent schedule, the
 * vehicles on a lot, the tenants who sign. Keys in both are token names as they
 * appear between the braces, so {@code term.rate} and not {@code rate}.
 */
public record TokenValues(
        Map<String, String> scalars,
        Map<String, List<Map<String, String>>> lists
) {

    public TokenValues {
        scalars = (scalars == null) ? Map.of() : Map.copyOf(scalars);
        lists = (lists == null) ? Map.of() : Map.copyOf(lists);
    }

    public static TokenValues of(Map<String, String> scalars) {
        return new TokenValues(scalars, Map.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Null when the token has no value, which the renderer reports rather than printing blank. */
    public String scalar(String token) {
        return scalars.get(token);
    }

    /** Null when no such list was supplied -- a clause repeating over it renders nothing. */
    public List<Map<String, String>> list(String token) {
        return lists.get(token);
    }

    public static final class Builder {
        private final Map<String, String> scalars = new LinkedHashMap<>();
        private final Map<String, List<Map<String, String>>> lists = new LinkedHashMap<>();

        public Builder put(String token, String value) {
            scalars.put(token, value);
            return this;
        }

        /** Rows are keyed by the row token names the clause writes inside its repeat block. */
        public Builder putList(String token, List<Map<String, String>> rows) {
            lists.put(token, List.copyOf(rows));
            return this;
        }

        public TokenValues build() {
            return new TokenValues(scalars, lists);
        }
    }
}
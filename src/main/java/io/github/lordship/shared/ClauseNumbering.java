package io.github.lordship.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a clause's position into the words "9.", "C." or "9C".
 *
 * <p>A pattern names levels by number and picks a style after a colon:
 * {@code {1}} is the level-1 number as digits, {@code {2:A}} the level-2 number
 * as a capital letter, {@code {3:i}} the level-3 number in small roman. Anything
 * outside braces prints as written, so {@code ({2:a})} prints "(c)".
 *
 * <p>Here rather than in {@code instruments} for the same reason as
 * {@link TokenSyntax}: the section editor validates a pattern when it is saved
 * and the freeze prints it, and the two must read it the same way.
 */
public final class ClauseNumbering {

    // sql: document_section.number_formats and cite_formats, one pattern per level
    public static final Pattern PLACE = Pattern.compile("\\{(\\d)(?::([^}]*))?}");

    private static final String STYLES = "1AaIi";

    private ClauseNumbering() {}

    /**
     * @param path the clause's number at each level, outermost first -- the
     *             faucet clause under "9. Water" is {@code [9, 2]}
     */
    public static String format(String pattern, List<Integer> path) {
        Matcher place = PLACE.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (place.find()) {
            int level = Integer.parseInt(place.group(1));
            String style = place.group(2) == null ? "1" : place.group(2);
            String printed = (level >= 1 && level <= path.size() && STYLES.contains(style) && style.length() == 1)
                    ? styled(path.get(level - 1), style.charAt(0))
                    : place.group(0); // left standing, like a token nothing could fill
            place.appendReplacement(out, Matcher.quoteReplacement(printed));
        }
        place.appendTail(out);
        return out.toString();
    }

    /**
     * What is wrong with a pattern meant for {@code level}, empty when nothing is.
     * A level-2 pattern may name levels 1 and 2 but not 3, which has no number yet.
     */
    public static List<String> problemsIn(String pattern, int level) {
        List<String> problems = new ArrayList<>();
        if (pattern == null || pattern.isBlank()) {
            problems.add("empty");
            return problems;
        }
        Matcher place = PLACE.matcher(pattern);
        boolean namesItsOwnLevel = false;
        while (place.find()) {
            int named = Integer.parseInt(place.group(1));
            String style = place.group(2);
            if (named < 1 || named > level) {
                problems.add(place.group(0));
            } else if (style != null && (style.length() != 1 || !STYLES.contains(style))) {
                problems.add(place.group(0));
            }
            namesItsOwnLevel |= named == level;
        }
        if (!namesItsOwnLevel) {
            // "9." for every clause at level 2 would number A, B and C all the same
            problems.add("{" + level + "} missing");
        }
        return List.copyOf(problems);
    }

    static String styled(int number, char style) {
        // 0 stands for an unnumbered heading ("Water:") above lettered items, so
        // they print "A." and are cited "A" rather than "0A".
        if (number == 0) {
            return "";
        }
        return switch (style) {
            case 'A' -> letters(number);
            case 'a' -> letters(number).toLowerCase();
            case 'I' -> roman(number);
            case 'i' -> roman(number).toLowerCase();
            default -> Integer.toString(number);
        };
    }

    /** A..Z, then AA, AB -- the way a 27th item is lettered in a spreadsheet. */
    static String letters(int number) {
        StringBuilder out = new StringBuilder();
        int n = number;
        while (n > 0) {
            n--;
            out.insert(0, (char) ('A' + n % 26));
            n /= 26;
        }
        return out.toString();
    }

    static String roman(int number) {
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        int n = number;
        for (int i = 0; i < values.length; i++) {
            while (n >= values[i]) {
                out.append(numerals[i]);
                n -= values[i];
            }
        }
        return out.toString();
    }
}

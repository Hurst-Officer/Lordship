package io.github.lordship.instruments;

import io.github.lordship.shared.DocumentToken;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Turns a value into the words that go on the page.
 *
 * <p>The half of resolution that has no dependencies: every method here takes a
 * figure and returns a string. What it does NOT do is decide which figure --
 * that is the gathering half's job. Keeping them apart means the formatting a
 * lease depends on can be tested against a list of numbers rather than a
 * database, which matters because these strings are read aloud in court.
 *
 * <p>Spelling numbers out is not decoration. A lease states a figure twice --
 * "four thousand two hundred dollars ($4,200.00)" -- so that a later alteration
 * of the digits contradicts the words. Which is also why the words are
 * generated from the same BigDecimal as the digits and never typed.
 */
public final class TokenFormatter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    private static final String[] ONES = {
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen"};

    private static final String[] TENS = {
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    private TokenFormatter() {}

    /**
     * Formats per the token's own declared Format, so a caller never decides how
     * something prints -- {@code DocumentToken} already said.
     *
     * <p>ENUM and TEXT pass through: a method name prints as the clause author
     * wrote it, and a clause conditions on the raw value rather than a prettied
     * one.
     */
    public static String format(DocumentToken token, Object value) {
        if (value == null) {
            return null;
        }
        return switch (token.format()) {
            case MONEY -> money(asDecimal(value));
            case MONEY_WORDS -> moneyInWords(asDecimal(value));
            case INTEGER -> String.valueOf(asDecimal(value).setScale(0, RoundingMode.HALF_UP));
            case INTEGER_WORDS -> integerInWords(asDecimal(value).intValueExact());
            case ORDINAL -> ordinal(asDecimal(value).intValueExact());
            case PERCENT -> percent(asDecimal(value));
            case PERCENT_WORDS -> percentInWords(asDecimal(value));
            case DATE -> date((LocalDate) value);
            case LIST -> list(asStrings(value));
            case TEXT, ENUM -> String.valueOf(value);
        };
    }

    // ---- money ---------------------------------------------------------------

    /** $4,200.00 -- always two places, always grouped. */
    public static String money(BigDecimal amount) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        return (scaled.signum() < 0 ? "-$" : "$")
                + String.format(Locale.US, "%,.2f", scaled.abs());
    }

    /**
     * four thousand two hundred dollars, or with cents, ... and fifty cents.
     *
     * <p>Whole dollars deliberately say nothing about cents rather than "and no
     * cents" -- a lease that reads "six hundred fifty dollars" is what a person
     * would write, and the digits beside it carry the precision.
     */
    public static String moneyInWords(BigDecimal amount) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        String sign = scaled.signum() < 0 ? "negative " : "";
        BigDecimal abs = scaled.abs();

        long dollars = abs.longValue();
        int cents = abs.subtract(BigDecimal.valueOf(dollars))
                .movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();

        String words = sign + integerInWords(dollars) + plural(" dollar", dollars);
        if (cents == 0) {
            return words;
        }
        return words + " and " + integerInWords(cents) + plural(" cent", cents);
    }

    // ---- numbers -------------------------------------------------------------

    /** 1st, 2nd, 3rd, 4th -- and 11th, 12th, 13th, which are the ones people get wrong. */
    public static String ordinal(int n) {
        int lastTwo = Math.abs(n) % 100;
        int last = Math.abs(n) % 10;
        String suffix = (lastTwo >= 11 && lastTwo <= 13) ? "th"
                : switch (last) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }

    public static String integerInWords(long n) {
        if (n < 0) {
            return "negative " + integerInWords(-n);
        }
        if (n < 20) {
            return ONES[(int) n];
        }
        if (n < 100) {
            long tens = n / 10;
            long rest = n % 10;
            return TENS[(int) tens] + (rest == 0 ? "" : "-" + ONES[(int) rest]);
        }
        if (n < 1_000) {
            return chunk(n, 100, "hundred");
        }
        if (n < 1_000_000) {
            return chunk(n, 1_000, "thousand");
        }
        if (n < 1_000_000_000) {
            return chunk(n, 1_000_000, "million");
        }
        return chunk(n, 1_000_000_000, "billion");
    }

    private static String chunk(long n, long unit, String name) {
        long count = n / unit;
        long rest = n % unit;
        String words = integerInWords(count) + " " + name;
        return rest == 0 ? words : words + " " + integerInWords(rest);
    }

    // ---- percent -------------------------------------------------------------

    /** 1.5% -- trailing zeros trimmed, so 4.00 prints as 4%. */
    public static String percent(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString() + "%";
    }

    /**
     * one and one half percent.
     *
     * <p>Halves and quarters get their names because that is how a lease says
     * them. Anything else falls back to "one point seven five percent", which is
     * clumsy but unambiguous -- and a rate that awkward is worth an author
     * noticing.
     */
    public static String percentInWords(BigDecimal value) {
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
        long whole = scaled.longValue();
        BigDecimal fraction = scaled.subtract(BigDecimal.valueOf(whole));

        if (fraction.signum() == 0) {
            return integerInWords(whole) + " percent";
        }

        String named = switch (fraction.movePointRight(2).intValueExact()) {
            case 25 -> "one quarter";
            case 50 -> "one half";
            case 75 -> "three quarters";
            default -> null;
        };
        if (named != null) {
            return integerInWords(whole) + " and " + named + " percent";
        }

        String digits = fraction.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toPlainString();
        StringBuilder spelled = new StringBuilder(integerInWords(whole)).append(" point");
        for (char c : digits.toCharArray()) {
            spelled.append(" ").append(ONES[c - '0']);
        }
        return spelled + " percent";
    }

    // ---- dates and lists -----------------------------------------------------

    /** November 1, 2026. */
    public static String date(LocalDate value) {
        return value.format(DATE);
    }

    /**
     * water, sewer and trash.
     *
     * <p>No serial comma: it is the house style in the leases this replaces, and
     * a lease is not the place to start a punctuation argument.
     */
    public static String list(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1))
                + " and " + items.get(items.size() - 1);
    }

    // ---- internals -----------------------------------------------------------

    private static String plural(String noun, long count) {
        return count == 1 ? noun : noun + "s";
    }

    private static BigDecimal asDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStrings(Object value) {
        return (value instanceof List<?> list)
                ? (List<String>) list
                : List.of(String.valueOf(value));
    }
}
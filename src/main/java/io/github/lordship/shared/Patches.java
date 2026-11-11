package io.github.lordship.shared;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * Reads one value out of a PATCH body ({@code Map<String, Object>}), where JSON
 * has already turned it into a String, Number, Boolean or null. Numbers use a
 * dot for decimals and no thousands separators; Glade sends them as text that way.
 * A JSON number in a map body arrives as a double, so only text keeps every digit.
 *
 * <p>Each method names the field it was reading, so a refusal says which box is
 * wrong. A number becomes a BigDecimal from its written digits.
 */
public final class Patches {

    private Patches() {}

    /** Trimmed text, or null when blank */
    public static String text(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().strip();
        return s.isEmpty() ? null : s;
    }

    /** Trimmed text that must be there */
    public static String requiredText(Object raw, String field) {
        String s = text(raw);
        if (s == null) throw InvalidRequest.onField(field, "value.required");
        return s;
    }

    public static LocalDate date(Object raw, String field) {
        String s = text(raw);
        if (s == null) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw InvalidRequest.onField(field, "value.not_a_date", s);
        }
    }

    public static BigDecimal decimal(Object raw, String field) {
        if (raw == null) return null;
        String s = raw.toString().strip();
        if (s.isEmpty()) return null;
        // "12,5" is 12.5 in Spanish and 125 in English: refuse it rather than guess
        if (s.indexOf(',') >= 0) throw InvalidRequest.onField(field, "value.not_a_number", raw.toString());
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw InvalidRequest.onField(field, "value.not_a_number", raw.toString());
        }
    }

    /**
     * The value, when a NUMERIC(precision, scale) column holds it exactly. Postgres
     * would round extra decimals without a word (12.345 becomes 12.35); here they
     * are refused instead, and so is a number too big for the column.
     */
    public static BigDecimal fits(BigDecimal value, int precision, int scale, String field) {
        if (value == null) return null;
        BigDecimal plain = value.stripTrailingZeros();
        if (plain.scale() > scale) throw InvalidRequest.onField(field, "value.too_many_decimals", scale);
        // long: a scale near Integer.MIN_VALUE (1E+2147483647) would wrap an int and slip past the check below
        long wholeDigits = (long) plain.precision() - plain.scale();
        if (wholeDigits > precision - scale) throw InvalidRequest.onField(field, "value.too_large");
        return value;
    }

    public static Long whole(Object raw, String field) {
        BigDecimal d = decimal(raw, field);
        if (d == null) return null;
        try {
            return d.longValueExact();
        } catch (ArithmeticException e) {
            throw InvalidRequest.onField(field, "value.not_whole", raw.toString());
        }
    }

    public static Double number(Object raw, String field) {
        BigDecimal d = decimal(raw, field);
        return d == null ? null : d.doubleValue();
    }

    public static Boolean bool(Object raw, String field) {
        if (raw == null) return null;
        if (raw instanceof Boolean b) return b;
        String s = raw.toString().strip().toLowerCase();
        if (s.equals("true")) return true;
        if (s.equals("false")) return false;
        throw InvalidRequest.onField(field, "value.not_true_false", raw.toString());
    }

    public static UUID id(Object raw, String field) {
        String s = text(raw);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw InvalidRequest.onField(field, "request.not_a_uuid", s);
        }
    }

    public static <E extends Enum<E>> E choice(Object raw, Class<E> type, String field) {
        String s = text(raw);
        if (s == null) return null;
        try {
            return Enum.valueOf(type, s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw InvalidRequest.onField(field, "value.not_a_choice", s);
        }
    }

    /**
     * Builds {@code UPDATE table SET a = :a, b = :b::some_enum WHERE uuid = :uuid
     * AND deleted_at IS NULL RETURNING *} for the given columns. Column names come
     * from the caller's own allow-list, never from the request.
     *
     * @param casts          column → Postgres type, for enum columns
     * @param touchUpdatedAt whether the table has an updated_at to set
     */
    public static String updateSql(String table, Map<String, Object> changes, Map<String, String> casts,
                                   boolean touchUpdatedAt) {
        StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
        changes.keySet().forEach(col -> {
            sql.append(col).append(" = :").append(col);
            String cast = casts.get(col);
            if (cast != null) sql.append("::").append(cast);
            sql.append(", ");
        });
        if (touchUpdatedAt) {
            sql.append("updated_at = now()");
        } else {
            sql.setLength(sql.length() - 2);
        }
        sql.append(" WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *");
        return sql.toString();
    }
}

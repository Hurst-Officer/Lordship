package io.github.lordship.shared;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PatchesTest {

    // ── fits: a number a NUMERIC(12,2) column holds exactly ──

    @Test
    void fits_refusesMoreDecimalsThanTheColumnKeeps() {
        InvalidRequest refused = assertThrows(InvalidRequest.class,
                () -> Patches.fits(new BigDecimal("12.345"), 12, 2, "amount"));

        assertEquals("value.too_many_decimals", refused.problem().code());
        assertEquals("amount", refused.problem().field());
    }

    @Test
    void fits_refusesANumberTooBigForTheColumn() {
        // eleven whole digits, where NUMERIC(12,2) has room for ten
        InvalidRequest refused = assertThrows(InvalidRequest.class,
                () -> Patches.fits(new BigDecimal("12345678901.00"), 12, 2, "amount"));

        assertEquals("value.too_large", refused.problem().code());
    }

    @Test
    void fits_refusesAHugeExponent_whoseDigitCountDoesNotFitAnInt() {
        // 1 followed by 2,147,483,647 zeros: counted in an int, the whole digits wrapped below zero and the number passed
        InvalidRequest refused = assertThrows(InvalidRequest.class,
                () -> Patches.fits(new BigDecimal("1E+2147483647"), 12, 2, "amount"));

        assertEquals("value.too_large", refused.problem().code());
    }

    @Test
    void fits_returnsAFittingValueAsGiven() {
        BigDecimal largestThatFits = new BigDecimal("9999999999.99");
        BigDecimal trailingZeros = new BigDecimal("12.3400");

        assertSame(largestThatFits, Patches.fits(largestThatFits, 12, 2, "amount"));
        // trailing zeros are not extra decimals
        assertSame(trailingZeros, Patches.fits(trailingZeros, 12, 2, "amount"));
        assertNull(Patches.fits(null, 12, 2, "amount"));
    }

    // ── whole and decimal: one value out of a PATCH body ──

    @Test
    void whole_readsTextAndJsonNumbers_andRefusesAFraction() {
        assertEquals(Long.valueOf(4410), Patches.whole("4410", "reading"));
        // a JSON number in a map body arrives as an Integer or a Double
        assertEquals(Long.valueOf(4410), Patches.whole(4410, "reading"));
        assertEquals(Long.valueOf(4410), Patches.whole(4410.0, "reading"));
        assertNull(Patches.whole("  ", "reading"));

        InvalidRequest refused = assertThrows(InvalidRequest.class, () -> Patches.whole("4410.5", "reading"));
        assertEquals("value.not_whole", refused.problem().code());
    }

    @Test
    void decimal_refusesAComma_ratherThanGuessWhichLanguageWroteTheNumber() {
        // "12,5" is 12.5 in Spanish and 125 in English
        InvalidRequest refused = assertThrows(InvalidRequest.class, () -> Patches.decimal("12,5", "amount"));
        assertEquals("value.not_a_number", refused.problem().code());

        assertEquals(new BigDecimal("12.50"), Patches.decimal(" 12.50 ", "amount"));
    }

    // ── updateSql ──

    @Test
    void updateSql_setsEachColumn_castsEnumColumns_andTouchesUpdatedAtOnlyWhenAsked() {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("title", "North main");
        changes.put("unit", "GAL");
        Map<String, String> casts = Map.of("unit", "meter_unit");

        assertEquals("UPDATE meter_setting SET title = :title, unit = :unit::meter_unit, updated_at = now()"
                        + " WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *",
                Patches.updateSql("meter_setting", changes, casts, true));
        // a table with no updated_at: the SET list ends at the last column, with no comma left behind
        assertEquals("UPDATE meter_setting SET title = :title, unit = :unit::meter_unit"
                        + " WHERE uuid = :uuid AND deleted_at IS NULL RETURNING *",
                Patches.updateSql("meter_setting", changes, casts, false));
    }
}

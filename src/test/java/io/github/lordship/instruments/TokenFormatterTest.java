package io.github.lordship.instruments;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TokenFormatterTest {

    // ---- money ---------------------------------------------------------------

    @Test
    void money_shouldGroupAndAlwaysShowTwoPlaces() {
        assertEquals("$4,200.00", TokenFormatter.money(new BigDecimal("4200")));
        assertEquals("$4,542.72", TokenFormatter.money(new BigDecimal("4542.72")));
        assertEquals("$65.00", TokenFormatter.money(new BigDecimal("65")));
        assertEquals("$0.00", TokenFormatter.money(BigDecimal.ZERO));
    }

    @Test
    void money_shouldPutTheSignBeforeTheSymbol() {
        // -$50.00 rather than $-50.00, which is how a ledger reads
        assertEquals("-$50.00", TokenFormatter.money(new BigDecimal("-50")));
    }

    @Test
    void moneyInWords_shouldSpellTheCommercialLeasesSchedule() {
        // The figures that appear twice on the lease -- digits and words -- so
        // that altering one contradicts the other
        assertEquals("four thousand two hundred dollars",
                TokenFormatter.moneyInWords(new BigDecimal("4200")));
        assertEquals("four thousand three hundred sixty-eight dollars",
                TokenFormatter.moneyInWords(new BigDecimal("4368")));
        assertEquals("four thousand five hundred forty-two dollars and seventy-two cents",
                TokenFormatter.moneyInWords(new BigDecimal("4542.72")));
        assertEquals("four thousand nine hundred thirteen dollars and forty-one cents",
                TokenFormatter.moneyInWords(new BigDecimal("4913.41")));
    }

    @Test
    void moneyInWords_shouldSayNothingAboutCents_whenThereAreNone() {
        // "six hundred fifty dollars" is what a person writes; the digits
        // beside it carry the precision
        assertEquals("six hundred fifty dollars",
                TokenFormatter.moneyInWords(new BigDecimal("650.00")));
    }

    @Test
    void moneyInWords_shouldUseTheSingular_forOneDollarAndOneCent() {
        assertEquals("one dollar", TokenFormatter.moneyInWords(new BigDecimal("1")));
        assertEquals("one dollar and one cent", TokenFormatter.moneyInWords(new BigDecimal("1.01")));
    }

    @Test
    void moneyInWords_shouldHandleZeroAndLargeAmounts() {
        assertEquals("zero dollars", TokenFormatter.moneyInWords(BigDecimal.ZERO));
        assertEquals("one million dollars", TokenFormatter.moneyInWords(new BigDecimal("1000000")));
        assertEquals("nine hundred ninety-nine thousand nine hundred ninety-nine dollars "
                        + "and ninety-nine cents",
                TokenFormatter.moneyInWords(new BigDecimal("999999.99")));
    }

    // ---- ordinals ------------------------------------------------------------

    @Test
    void ordinal_shouldHandleTheTeens() {
        // 11th, 12th and 13th are the ones a naive last-digit rule gets wrong
        assertEquals("11th", TokenFormatter.ordinal(11));
        assertEquals("12th", TokenFormatter.ordinal(12));
        assertEquals("13th", TokenFormatter.ordinal(13));
        assertEquals("111th", TokenFormatter.ordinal(111));
    }

    @Test
    void ordinal_shouldHandleTheOrdinaryCases() {
        assertEquals("1st", TokenFormatter.ordinal(1));
        assertEquals("2nd", TokenFormatter.ordinal(2));
        assertEquals("3rd", TokenFormatter.ordinal(3));
        assertEquals("5th", TokenFormatter.ordinal(5));
        assertEquals("21st", TokenFormatter.ordinal(21));
        assertEquals("101st", TokenFormatter.ordinal(101));
    }

    // ---- integers ------------------------------------------------------------

    @Test
    void integerInWords_shouldHyphenateCompoundTens() {
        assertEquals("twenty-one", TokenFormatter.integerInWords(21));
        assertEquals("ninety-nine", TokenFormatter.integerInWords(99));
        assertEquals("twenty", TokenFormatter.integerInWords(20));
    }

    @Test
    void integerInWords_shouldNotInsertAnAndBeforeTheRemainder() {
        // US legal style: "one hundred one", not "one hundred and one"
        assertEquals("one hundred one", TokenFormatter.integerInWords(101));
        assertEquals("one hundred fifteen", TokenFormatter.integerInWords(115));
        assertEquals("four thousand two hundred", TokenFormatter.integerInWords(4200));
    }

    // ---- percent -------------------------------------------------------------

    @Test
    void percent_shouldTrimTrailingZeros() {
        // The escalation column holds 4.00; the lease should say 4%
        assertEquals("4%", TokenFormatter.percent(new BigDecimal("4.00")));
        assertEquals("1.5%", TokenFormatter.percent(new BigDecimal("1.50")));
        assertEquals("100%", TokenFormatter.percent(new BigDecimal("100.00")));
    }

    @Test
    void percentInWords_shouldNameHalvesAndQuarters() {
        assertEquals("one and one half percent", TokenFormatter.percentInWords(new BigDecimal("1.5")));
        assertEquals("four percent", TokenFormatter.percentInWords(new BigDecimal("4")));
        assertEquals("three and one quarter percent", TokenFormatter.percentInWords(new BigDecimal("3.25")));
        assertEquals("two and three quarters percent", TokenFormatter.percentInWords(new BigDecimal("2.75")));
    }

    @Test
    void percentInWords_shouldFallBackToDigits_forAnAwkwardRate() {
        // Clumsy on purpose -- a rate this shape is worth an author noticing
        assertEquals("one point three three percent", TokenFormatter.percentInWords(new BigDecimal("1.33")));
    }

    // ---- dates and lists -----------------------------------------------------

    @Test
    void date_shouldPrintUsLegalStyle() {
        assertEquals("November 1, 2026", TokenFormatter.date(LocalDate.of(2026, 11, 1)));
        assertEquals("October 31, 2031", TokenFormatter.date(LocalDate.of(2031, 10, 31)));
    }

    @Test
    void list_shouldJoinWithoutASerialComma() {
        assertEquals("water, sewer and trash",
                TokenFormatter.list(List.of("water", "sewer", "trash")));
        assertEquals("water and sewer", TokenFormatter.list(List.of("water", "sewer")));
        assertEquals("water", TokenFormatter.list(List.of("water")));
        assertEquals("", TokenFormatter.list(List.of()));
    }
}
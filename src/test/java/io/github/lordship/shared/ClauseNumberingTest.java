package io.github.lordship.shared;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ClauseNumberingTest {

    @Test
    void format_shouldPrintEachLevelInItsOwnStyle() {
        // Arrange
        List<Integer> path = List.of(9, 3, 2);

        // Act / Assert
        assertEquals("9.", ClauseNumbering.format("{1}.", path));
        assertEquals("C.", ClauseNumbering.format("{2:A}.", path));
        assertEquals("(c)", ClauseNumbering.format("({2:a})", path));
        assertEquals("(ii)", ClauseNumbering.format("({3:i})", path));
        assertEquals("IX", ClauseNumbering.format("{1:I}", path));
        assertEquals("9C(ii)", ClauseNumbering.format("{1}{2:A}({3:i})", path));
    }

    @Test
    void format_shouldPrintLiteralText_outsideTheBraces() {
        assertEquals("Article 9, Section 3", ClauseNumbering.format("Article {1}, Section {2}", List.of(9, 3)));
    }

    @Test
    void format_shouldLeaveAPlaceStanding_whenTheClauseHasNoNumberAtThatLevel() {
        // Arrange -- a top-level clause has no level-2 number to print
        List<Integer> path = List.of(4);

        // Act
        String printed = ClauseNumbering.format("{1}{2:A}", path);

        // Assert -- visible on a preview rather than silently blank
        assertEquals("4{2:A}", printed);
    }

    @Test
    void letters_shouldContinuePastZ_theWayASpreadsheetDoes() {
        assertEquals("A", ClauseNumbering.letters(1));
        assertEquals("Z", ClauseNumbering.letters(26));
        assertEquals("AA", ClauseNumbering.letters(27));
        assertEquals("AB", ClauseNumbering.letters(28));
    }

    @Test
    void roman_shouldUseSubtractiveNotation() {
        assertEquals("IV", ClauseNumbering.roman(4));
        assertEquals("XIV", ClauseNumbering.roman(14));
        assertEquals("XL", ClauseNumbering.roman(40));
    }

    @Test
    void problemsIn_shouldAcceptTheDefaults() {
        assertEquals(List.of(), ClauseNumbering.problemsIn("{1}.", 1));
        assertEquals(List.of(), ClauseNumbering.problemsIn("{2:A}.", 2));
        assertEquals(List.of(), ClauseNumbering.problemsIn("{1}{2:A}({3:i})", 3));
    }

    @Test
    void problemsIn_shouldRefuseALevelThatHasNoNumberYet() {
        assertEquals(List.of("{3}"), ClauseNumbering.problemsIn("{2}{3}", 2));
    }

    @Test
    void problemsIn_shouldRefuseAnUnknownStyle() {
        assertEquals(List.of("{2:x}"), ClauseNumbering.problemsIn("{2:x}", 2));
    }

    @Test
    void problemsIn_shouldRefuseAPatternThatNeverNamesItsOwnLevel() {
        // Arrange -- "{1}." at level 2 would print 9. for A, B and C alike
        assertEquals(List.of("{2} missing"), ClauseNumbering.problemsIn("{1}.", 2));
    }
}

package io.github.lordship.shared;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class StyleRulesTest {

    @Test
    void problemsIn_shouldAcceptPlainDeclarations() {
        assertEquals(List.of(), StyleRules.problemsIn(
                "font-weight: bold; font-size: 13pt; border: 2px solid #000; padding: 3mm;"));
    }

    @Test
    void problemsIn_shouldRefuseABraceThatLeavesTheRule() {
        assertEquals(List.of("{", "}"), StyleRules.problemsIn("color: red } body { display: none"));
    }

    @Test
    void problemsIn_shouldRefuseAnythingThatFetches() {
        assertEquals(List.of("@"), StyleRules.problemsIn("@import 'x.css';"));
        assertEquals(List.of("url("), StyleRules.problemsIn("background: URL(https://example.com/a.png);"));
    }

    @Test
    void problemsIn_shouldRefuseAnEscape_thatCouldSpellTheRest() {
        assertEquals(List.of("\\"), StyleRules.problemsIn("background: u\\72l(x);"));
    }

    @Test
    void problemsIn_shouldRefuseClosingTheStyleTag() {
        assertEquals(List.of("<"), StyleRules.problemsIn("color: red; </style><script>"));
    }
}

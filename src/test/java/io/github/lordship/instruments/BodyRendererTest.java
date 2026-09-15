package io.github.lordship.instruments;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class BodyRendererTest {

    // ---- scalars -------------------------------------------------------------

    @Test
    void render_shouldSubstituteAScalarToken() {
        // Arrange
        TokenValues values = TokenValues.builder()
                .put("term.rate", "$4,200.00")
                .build();

        // Act
        BodyRenderer.Rendered out = BodyRenderer.render(
                "Tenant shall pay {{term.rate}} per month.", values);

        // Assert
        assertEquals("Tenant shall pay $4,200.00 per month.", out.text());
        assertTrue(out.isComplete());
    }

    @Test
    void render_shouldNotTreatADollarSignAsAReplacementGroup() {
        // Arrange -- every money token starts with '$', which is a metacharacter
        // in a regex replacement. Unquoted, this throws or silently mangles.
        TokenValues values = TokenValues.builder()
                .put("term.rate", "$4,200.00")
                .put("term.late_fee_amount", "$65.00")
                .build();

        // Act
        BodyRenderer.Rendered out = BodyRenderer.render(
                "{{term.rate}} rent, {{term.late_fee_amount}} late.", values);

        // Assert
        assertEquals("$4,200.00 rent, $65.00 late.", out.text());
    }

    @Test
    void render_shouldLeaveAnUnresolvedTokenStanding_andReportIt() {
        // Arrange -- a visible {{term.rate}} is caught by eye on review;
        // a silent gap in a signed lease is not
        TokenValues values = TokenValues.of(Map.of());

        // Act
        BodyRenderer.Rendered out = BodyRenderer.render("Rent is {{term.rate}}.", values);

        // Assert
        assertEquals("Rent is {{term.rate}}.", out.text());
        assertFalse(out.isComplete());
        assertEquals(List.of("term.rate"), out.unresolved());
    }

    @Test
    void render_shouldReportEachMissingTokenOnce() {
        // Arrange
        TokenValues values = TokenValues.of(Map.of());

        // Act
        BodyRenderer.Rendered out = BodyRenderer.render(
                "{{term.rate}} ... {{term.rate}} ... {{lot.lot_number}}", values);

        // Assert -- the office worker sees each missing figure named once
        assertEquals(List.of("term.rate", "lot.lot_number"), out.unresolved());
    }

    // ---- repeat blocks -------------------------------------------------------

    @Test
    void render_shouldRepeatABlockOncePerRow() {
        // Arrange -- the commercial lease's five year rent schedule
        TokenValues values = TokenValues.builder()
                .putList("term.rent_schedule", List.of(
                        Map.of("rent_step.period", "Nov 1 2026 - Oct 31 2027",
                                "rent_step.rate", "$4,200.00"),
                        Map.of("rent_step.period", "Nov 1 2027 - Oct 31 2028",
                                "rent_step.rate", "$4,368.00"),
                        Map.of("rent_step.period", "Nov 1 2028 - Oct 31 2029",
                                "rent_step.rate", "$4,542.72")))
                .build();

        // Act
        BodyRenderer.Rendered out = BodyRenderer.render(
                "SCHEDULE:\n{{#each term.rent_schedule}}{{rent_step.period}}  {{rent_step.rate}}\n{{/each}}",
                values);

        // Assert
        assertEquals("""
                SCHEDULE:
                Nov 1 2026 - Oct 31 2027  $4,200.00
                Nov 1 2027 - Oct 31 2028  $4,368.00
                Nov 1 2028 - Oct 31 2029  $4,542.72
                """, out.text());
        assertTrue(out.isComplete());
    }

    @Test
    void render_shouldResolveADocumentTokenInsideARepeatBlock() {
        // Arrange -- a repeated line may still name something document-wide
        TokenValues values = TokenValues.builder()
                .put("property.community_name", "Harbor View")
                .putList("term.rent_schedule", List.of(
                        Map.of("rent_step.rate", "$4,200.00"),
                        Map.of("rent_step.rate", "$4,368.00")))
                .build();

        // Act
        BodyRenderer.Rendered out = BodyRenderer.render(
                "{{#each term.rent_schedule}}{{property.community_name}}: {{rent_step.rate}}\n{{/each}}",
                values);

        // Assert
        assertEquals("Harbor View: $4,200.00\nHarbor View: $4,368.00\n", out.text());
        assertTrue(out.isComplete());
    }

    @Test
    void render_shouldLetARowShadowADocumentValueOfTheSameName() {
        // Arrange -- the inner scope is the more specific statement
        TokenValues values = TokenValues.builder()
                .put("term.rate", "$650.00")
                .putList("term.rent_schedule", List.of(
                        Map.of("term.rate", "$4,200.00")))
                .build();

        // Act
        BodyRenderer.Rendered out = BodyRenderer.render(
                "{{#each term.rent_schedule}}{{term.rate}}{{/each}} (base {{term.rate}})", values);

        // Assert
        assertEquals("$4,200.00 (base $650.00)", out.text());
    }

    @Test
    void render_shouldRenderNothing_andReport_whenTheListWasNeverSupplied() {
        // Arrange
        TokenValues values = TokenValues.of(Map.of());

        // Act
        BodyRenderer.Rendered out = BodyRenderer.render(
                "SCHEDULE:\n{{#each term.rent_schedule}}{{rent_step.rate}}\n{{/each}}DONE", values);

        // Assert
        assertEquals("SCHEDULE:\nDONE", out.text());
        assertEquals(List.of("term.rent_schedule"), out.unresolved());
    }

    @Test
    void render_shouldRenderNothing_andNotReport_whenTheListIsEmpty() {
        // Arrange -- a lease with no vehicles listed has correctly said so
        TokenValues values = TokenValues.builder()
                .putList("lot.vehicles", List.of())
                .build();

        // Act
        BodyRenderer.Rendered out = BodyRenderer.render(
                "VEHICLES:\n{{#each lot.vehicles}}{{vehicle.plate}}\n{{/each}}END", values);

        // Assert
        assertEquals("VEHICLES:\nEND", out.text());
        assertTrue(out.isComplete());
    }

    @Test
    void render_shouldReportAMissingRowValue() {
        // Arrange -- one row is short a figure
        TokenValues values = TokenValues.builder()
                .putList("term.rent_schedule", List.of(
                        Map.of("rent_step.rate", "$4,200.00"),
                        Map.of()))
                .build();

        // Act
        BodyRenderer.Rendered out = BodyRenderer.render(
                "{{#each term.rent_schedule}}{{rent_step.rate}};{{/each}}", values);

        // Assert
        assertEquals("$4,200.00;{{rent_step.rate}};", out.text());
        assertEquals(List.of("rent_step.rate"), out.unresolved());
    }

    @Test
    void render_shouldKeepTwoRepeatBlocksSeparate() {
        // Arrange -- the reluctant quantifier is what stops the first {{/each}}
        // from being swallowed into one giant block
        TokenValues values = TokenValues.builder()
                .putList("term.rent_schedule", List.of(Map.of("rent_step.rate", "$4,200.00")))
                .putList("tenancy.tenants", List.of(Map.of("tenant.name", "Ada Lovelace")))
                .build();

        // Act
        BodyRenderer.Rendered out = BodyRenderer.render(
                "A{{#each term.rent_schedule}}{{rent_step.rate}}{{/each}}"
                        + "B{{#each tenancy.tenants}}{{tenant.name}}{{/each}}C", values);

        // Assert
        assertEquals("A$4,200.00BAda LovelaceC", out.text());
        assertTrue(out.isComplete());
    }

    // ---- repeatedLists -------------------------------------------------------

    @Test
    void repeatedLists_shouldNameTheListsABodyRepeatsOver() {
        // Act
        List<String> lists = BodyRenderer.repeatedLists(
                "{{#each term.rent_schedule}}x{{/each}} and {{#each lot.vehicles}}y{{/each}}");

        // Assert -- what the save-time validator needs to tell a row token from a typo
        assertEquals(List.of("term.rent_schedule", "lot.vehicles"), lists);
    }

    @Test
    void repeatedLists_shouldBeEmpty_forAnOrdinaryBody() {
        assertTrue(BodyRenderer.repeatedLists("Tenant shall pay {{term.rate}}.").isEmpty());
    }

    @Test
    void render_shouldHandleAnEmptyBody() {
        BodyRenderer.Rendered out = BodyRenderer.render("", TokenValues.of(Map.of()));
        assertEquals("", out.text());
        assertTrue(out.isComplete());
    }
}
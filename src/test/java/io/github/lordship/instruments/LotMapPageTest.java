package io.github.lordship.instruments;

import io.github.lordship.documenttemplate.DocumentSection;
import io.github.lordship.documenttemplate.TemplateClause;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The map from token to printed page: freeze keeps it, the renderer draws it. */
public class LotMapPageTest {

    private static final String MAP = "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>Lot 7</text></svg>";

    @Test
    void freeze_shouldKeepTheDrawingBesideTheBody_notInsideIt() {
        // Arrange -- the clause is a sentence and a map token
        DocumentFreeze.Frozen frozen = freeze(TokenValues.of(java.util.Map.of("lot.site_map", MAP)));

        // Act
        DocumentFreeze.FrozenClause clause = frozen.sections().get(0).clauses().get(0);

        // Assert -- the body keeps its words, the picture travels alongside
        assertEquals("The tenant's space is shown below.", clause.body());
        assertEquals(MAP, clause.drawing());
        assertTrue(frozen.isComplete());
    }

    @Test
    void freeze_shouldReportAMissingMap_ratherThanPrintingABlankPage() {
        // Arrange -- a park nobody has placed yet
        DocumentFreeze.Frozen frozen = freeze(TokenValues.of(java.util.Map.of()));

        // Act
        DocumentFreeze.FrozenClause clause = frozen.sections().get(0).clauses().get(0);

        // Assert
        assertNull(clause.drawing());
        assertEquals(List.of("lot.site_map"), clause.unresolved());
        assertFalse(frozen.isComplete());
    }

    @Test
    void render_shouldPrintTheDrawingAsMarkup_onAPageOfItsOwn() {
        // Arrange
        DocumentFreeze.Frozen frozen = freeze(TokenValues.of(java.util.Map.of("lot.site_map", MAP)));
        LeasePreview preview = new LeasePreview(UUID.randomUUID(), UUID.randomUUID(), "WA Lot Lease", 1, frozen);

        // Act
        String html = LeaseDocument.render(preview, "HS-0001");

        // Assert -- the svg reaches the page whole, inside its own block
        assertTrue(html.contains("<div class=\"map\"><svg"));
        assertTrue(html.contains("<text>Lot 7</text>"));
        assertFalse(html.contains("&lt;svg"), "the drawing must not be escaped as text");
    }

    @Test
    void render_shouldGiveAMapOnlySectionTheWholeSheet_withNoHeadingOverIt() {
        // Arrange -- the clause is the map and nothing else
        DocumentFreeze.Frozen frozen = freeze(TokenValues.of(java.util.Map.of("lot.site_map", MAP)), "");
        LeasePreview preview = new LeasePreview(UUID.randomUUID(), UUID.randomUUID(), "WA Lot Lease", 1, frozen);

        // Act
        String html = LeaseDocument.render(preview, "HS-0001");

        // Assert -- no title over the picture, and the drawing keeps the whole sheet
        assertFalse(html.contains("<h2>Lot Description</h2>"));
        assertTrue(html.contains("map-sheet"), "the sheet should be marked as a map sheet");
        assertTrue(html.contains(".sheet.map-sheet .map { margin: 0; }"),
                "the drawing is already cut to the printable area, so it needs no margin of its own");
    }

    @Test
    void render_shouldKeepTheHeading_whenTheSectionAlsoSaysSomething() {
        // Arrange -- a sentence next to the map means an ordinary titled page
        DocumentFreeze.Frozen frozen = freeze(TokenValues.of(java.util.Map.of("lot.site_map", MAP)));
        LeasePreview preview = new LeasePreview(UUID.randomUUID(), UUID.randomUUID(), "WA Lot Lease", 1, frozen);

        String html = LeaseDocument.render(preview, "HS-0001");

        assertTrue(html.contains("Lot Description</h2>"));
    }

    // ---- a one-clause document ----------------------------------------------

    private static DocumentFreeze.Frozen freeze(TokenValues values) {
        return freeze(values, "The tenant's space is shown below.\n\n");
    }

    private static DocumentFreeze.Frozen freeze(TokenValues values, String lead) {
        TemplateClause clause = new TemplateClause(
                UUID.randomUUID(), BigDecimal.ONE, "LOT_DESCRIPTION", null,
                lead + "{{lot.site_map}}",
                null, List.of(), true, "RCW 59.20.060", null,
                null, null, null, null, false, null, null);

        DocumentSection section = new DocumentSection(
                UUID.randomUUID(), BigDecimal.ONE, "Lot Description", "LOT_DESCRIPTION",
                true, true, true, "RCW 59.20.060", null, null, null,
                List.of(clause), null, null, null, null);

        return DocumentFreeze.freeze(List.of(section), values);
    }
}

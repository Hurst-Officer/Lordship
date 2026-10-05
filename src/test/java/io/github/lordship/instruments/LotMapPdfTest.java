package io.github.lordship.instruments;

import io.github.lordship.documenttemplate.DocumentSection;
import io.github.lordship.documenttemplate.TemplateClause;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real lot map, all the way to a PDF.
 *
 * <p>PdfRendererTest proves Batik can draw a rectangle and one line of text.
 * This proves it can draw what LotMapDrawing actually produces, which is a
 * harder ask: road names are rotated into place, the faded edges are
 * a linearGradient, and the drawing fills the printable area exactly.
 *
 * <p>Uses PDFBox 3 to read the result back. On PDFBox 2 the one line to change
 * is Loader.loadPDF(pdf) -> PDDocument.load(pdf).
 */
class LotMapPdfTest {

    // A park near Port Orchard. Lots are 15 m wide and 25 m deep, in one row.
    private static final double LNG0 = -122.6360;
    private static final double LAT0 = 47.5400;

    @Test
    void render_shouldPutTheDrawingInThePdf() throws IOException {
        // Arrange -- the same lease twice: once with a map, once with none
        byte[] withMap = PdfRenderer.toPdf(lease(LotMapDrawing.draw(park())));
        byte[] withoutMap = PdfRenderer.toPdf(lease(null));

        // Assert -- both are PDFs, and the drawn one carries a page of shapes
        assertEquals("%PDF", new String(withMap, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("%PDF", new String(withoutMap, 0, 4, StandardCharsets.US_ASCII));
        assertTrue(withMap.length > withoutMap.length + 2000,
                "the map does not look drawn: " + withMap.length + " vs " + withoutMap.length);
    }

    @Test
    void render_shouldKeepTheLeaseWordsSearchable() throws IOException {
        // Arrange -- the words outside the map. The ones inside it come out as
        // outlines rather than as text: the PDF engine draws svg glyphs as
        // shapes unless the font maps to one the PDF format already knows. They
        // print and they photocopy; they just cannot be searched.
        String svg = LotMapDrawing.draw(park());
        assertTrue(svg.contains("Bell Hollow"), "the drawing should carry the park name");
        assertTrue(svg.contains("Alder Loop"), "the drawing has no road name to lose");
        assertTrue(svg.contains(">53</text>"), "the drawing should carry the tenant's lot number");

        // Act
        String text = textIn(PdfRenderer.toPdf(lease(svg)));

        // Assert -- a lease nobody can search is a lease nobody can check
        assertTrue(text.contains("The monthly rent is $650.00"), "the lease text is missing: " + text);
    }

    @Test
    void render_shouldGiveTheMapASheetOfItsOwn() throws IOException {
        // Arrange / Act
        byte[] pdf = PdfRenderer.toPdf(lease(LotMapDrawing.draw(park())));

        // Assert -- the map did not get squeezed onto the end of the rent page
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(2, document.getNumberOfPages(),
                    "one page of lease, one of map. More means the map sheet kept its margins "
                            + "and the drawing no longer fits on it.");
        }
    }

    @Test
    void render_shouldDrawAParkTooBigForThePage() throws IOException {
        // Arrange -- 120 lots is 1.8 km, so the map runs off the page and fades.
        // The fade is a gradient the drawing refers to by id, which is the other
        // kind of reference that could go missing on the way into the PDF.
        String svg = LotMapDrawing.draw(longPark());
        assertTrue(svg.contains("linearGradient"), "a cropped map should fade");

        // Act / Assert
        assertEquals("%PDF", new String(PdfRenderer.toPdf(lease(svg)), 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void draw_shouldNotPointAtItselfById_exceptForTheFades() {
        // Arrange / Act
        String svg = LotMapDrawing.draw(park());

        // Assert -- an id reference in an href loses its namespace inside an
        // HTML page and brings the whole render down. url(#...) in a fill is
        // read from the style and survives; nothing else may refer by id.
        assertFalse(svg.contains("xlink"), "xlink does not survive the trip into the PDF");
        assertFalse(svg.contains("href"), "the drawing must not refer to its own ids by href");
    }

    // ---- a two-page lease: one clause, then the map --------------------------

    /** The lease HTML, with the drawing on its own sheet. A null drawing leaves it out. */
    private static String lease(String drawing) {
        TemplateClause words = clause("RENT", "The monthly rent is $650.00, due on the first.");
        TemplateClause map = clause("LOT_DESCRIPTION_MAP", "{{lot.site_map}}");

        // The map clause is the token and nothing else, which is what makes the
        // sheet full-bleed. A heading or a sentence beside it keeps the margins.

        TokenValues values = drawing == null
                ? TokenValues.of(Map.of())
                : TokenValues.of(Map.of("lot.site_map", drawing));

        DocumentFreeze.Frozen frozen = DocumentFreeze.freeze(
                List.of(section("Rent", "RENT", words),
                        section("Lot Description", "LOT_DESCRIPTION_MAP", map)),
                values);

        LeasePreview preview = new LeasePreview(
                UUID.randomUUID(), UUID.randomUUID(), "WA Lot Lease", 1, frozen);
        return LeaseDocument.render(preview, "HS-TEST-0001");
    }

    private static TemplateClause clause(String group, String body) {
        return new TemplateClause(
                UUID.randomUUID(), BigDecimal.ONE, group, null,
                body,
                null, List.of(), true, "RCW 59.20.060", null,
                null, null, null, null, false, null, null);
    }

    private static DocumentSection section(String title, String group, TemplateClause clause) {
        return new DocumentSection(
                UUID.randomUUID(), BigDecimal.ONE, title, group,
                true, true, true, "RCW 59.20.060", null, null, null,
                List.of(clause), null, null, null, null);
    }

    private static String textIn(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    // ---- the park ------------------------------------------------------------

    /** The same park stretched to 120 lots, which no page can hold at a readable size. */
    private static LotMapDrawing.Park longPark() {
        double[][] road = {
                {LNG0 - 0.0002, LAT0 + 0.00030},
                {LNG0 + 0.0002 * 120, LAT0 + 0.00030}
        };
        return new LotMapDrawing.Park(
                "Bell Hollow", "103", lots(120),
                List.of(new LotMapDrawing.Line("Alder Loop", 8, road)),
                List.of(), null, null,
                List.of("(c) OpenStreetMap contributors"),
                LocalDate.of(2026, 9, 29));
    }

    private static LotMapDrawing.Park park() {
        double[][] road = {
                {LNG0 - 0.0002, LAT0 + 0.00030},
                {LNG0 + 0.0002 * 6, LAT0 + 0.00030}
        };
        return new LotMapDrawing.Park(
                "Bell Hollow",
                "53",
                lots(6),
                List.of(new LotMapDrawing.Line("Alder Loop", 8, road)),
                List.of(),
                null,
                null,
                List.of("(c) OpenStreetMap contributors"),
                LocalDate.of(2026, 9, 29));
    }

    /** A row of rectangular lots, numbered from 51 so the numbers are easy to find. */
    private static List<LotMapDrawing.Lot> lots(int count) {
        List<LotMapDrawing.Lot> lots = new ArrayList<>(count);
        double wide = 0.0002;   // about 15 m of longitude here
        double deep = 0.000225; // about 25 m of latitude
        for (int i = 0; i < count; i++) {
            double left = LNG0 + i * wide;
            double[][] ring = {
                    {left, LAT0},
                    {left + wide * 0.95, LAT0},
                    {left + wide * 0.95, LAT0 - deep},
                    {left, LAT0 - deep}
            };
            lots.add(new LotMapDrawing.Lot(String.valueOf(51 + i), ring));
        }
        return lots;
    }
}

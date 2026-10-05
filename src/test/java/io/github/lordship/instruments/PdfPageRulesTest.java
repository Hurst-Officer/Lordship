package io.github.lordship.instruments;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the PDF engine will and will not do with a page, one thing at a time.
 *
 * <p>The map sheet is sized from the answers here. When it starts coming out on
 * two pages, these say whether the engine changed under us or the drawing did.
 */
class PdfPageRulesTest {

    /**
     * The biggest block that lands on one page, found by trying. Letter less the
     * lease's margins works out at 175.9 x 237.4, but that spills; the engine's
     * rounding costs a few millimetres. LotMapDrawing draws to these numbers.
     */
    private static final String MAP_SHEET = "width: 170.9mm; height: 230.4mm";
            //"width: 175.9mm; height: 237.4mm";

    private static final String LEASE_PAGE = "@page { size: letter; margin: 22mm 20mm 20mm 20mm; }";

    @Test
    void printableArea_shouldFitOnOnePage() throws IOException {
        // Arrange -- a block the size LotMapDrawing draws to. The whole map
        // sheet rests on this landing on one page.
        String html = page(LEASE_PAGE, "<div style=\"" + MAP_SHEET + "; background: #eeeeee;\"></div>");

        // Act / Assert
        assertEquals(1, pages(PdfRenderer.toPdf(html)),
                "the map is drawn at this size; if it no longer fits, resize it in LotMapDrawing");
    }

    @Test
    void namedPage_shouldKeepTheMarginsOfTheOrdinaryPage() throws IOException {
        // Arrange -- a named page asking for no margin at all, and a block the
        // size of the paper. Were the request honoured, this would be one page.
        String html = page(LEASE_PAGE + "\n@page full-bleed { margin: 0; }\n.bleed { page: full-bleed; }",
                "<div class=\"bleed\" style=\"width: 215.9mm; height: 279.4mm; background: #eeeeee;\"></div>");

        // Act / Assert -- it is not. The margins stay, the block overflows, and
        // that is why the map is cut to the printable area rather than to the
        // paper. A 1 here means the engine learned to do it and the map sheet
        // can go edge to edge after all.
        assertEquals(2, pages(PdfRenderer.toPdf(html)),
                "named pages now drop their margins -- the map sheet could be full-bleed");
    }

    @Test
    void page_shouldRenderAnInlineSvgAtTheMapSize() {
        // Arrange -- the map sheet, cut down to one lot
        String html = page(LEASE_PAGE + "\n.map svg { display: block; }",
                "<div class=\"map\">"
                        + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"175.9mm\" height=\"237.4mm\""
                        + " viewBox=\"0 0 175.9 237.4\" font-family=\"Helvetica, Arial, sans-serif\">"
                        + "<rect x=\"0\" y=\"0\" width=\"175.9\" height=\"237.4\" fill=\"#ffffff\"/>"
                        + "<rect x=\"20\" y=\"20\" width=\"60\" height=\"40\" fill=\"#f0dca8\" stroke=\"#333\"/>"
                        + "<text x=\"50\" y=\"44\" text-anchor=\"middle\" font-size=\"6\">53</text>"
                        + "</svg></div>");

        // Act / Assert
        assertPdf(PdfRenderer.toPdf(html));
    }

    @Test
    void ordinaryPage_shouldStillCountItsPages() {
        // Arrange -- the lease footer, which the map sheet must not disturb
        String html = page("""
                @page {
                  size: letter; margin: 22mm 20mm 20mm 20mm;
                  @bottom-center { content: "Page " counter(page) " of " counter(pages); font-size: 8pt; }
                  @bottom-right { content: "HS-TEST-0001-LLE"; font-size: 7.5pt; }
                }
                """,
                "<div>Page one.</div>");

        // Act / Assert
        assertPdf(PdfRenderer.toPdf(html));
    }

    private static String page(String css, String body) {
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><style>"
                + css + "</style></head><body>" + body + "</body></html>";
    }

    private static int pages(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        }
    }

    private static void assertPdf(byte[] pdf) {
        assertTrue(pdf.length > 0);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
    }
}

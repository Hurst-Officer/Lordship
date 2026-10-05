package io.github.lordship.instruments;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PdfRendererTest {

    @Test
    void toPdf_shouldProduceAPdf_fromOrdinaryHtml5() {
        // Arrange -- an unclosed meta tag, which strict XHTML would reject
        String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <style>@page { size: letter; margin: 1in; @bottom-right { content: "HS-TEST-0001-LLE"; } }</style>
                </head>
                <body><p>The monthly rent is <b>$650.00</b>.<br>Due on the 1st.</p></body>
                </html>
                """;

        // Act
        byte[] pdf = PdfRenderer.toPdf(html);

        // Assert
        assertTrue(pdf.length > 0);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void toPdf_shouldDrawInlineSvg_becauseTheLotMapIsOne() {
        // Arrange -- the same shapes the lot map page is made of. Without an SVG
        // drawer registered, openhtmltopdf drops this silently and the map page
        // prints empty, which is the kind of thing nobody notices until a lease
        // is in front of a tenant.
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="80mm" height="40mm" viewBox="0 0 80 40">
                <rect x="2" y="2" width="30" height="20" fill="#f3e2b8" stroke="#333" stroke-width="0.7"/>
                <text x="17" y="14" text-anchor="middle" font-size="4">7</text>
                </svg>
                """;
        String html = "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<style>@page { size: letter; margin: 20mm; }</style></head>"
                + "<body><div class=\"map\">" + svg + "</div></body></html>";

        // Act
        byte[] withMap = PdfRenderer.toPdf(html);
        byte[] withoutMap = PdfRenderer.toPdf(
                "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"></head><body></body></html>");

        // Assert -- the drawing reached the page: a drawn PDF is bigger than an
        // empty one by more than rounding.
        assertEquals("%PDF", new String(withMap, 0, 4, StandardCharsets.US_ASCII));
        assertTrue(withMap.length > withoutMap.length + 200,
                "the svg does not appear to have been drawn: " + withMap.length + " vs " + withoutMap.length);
    }
}

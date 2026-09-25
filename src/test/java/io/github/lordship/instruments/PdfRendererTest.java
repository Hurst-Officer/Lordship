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
}

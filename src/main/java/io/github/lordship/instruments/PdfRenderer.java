package io.github.lordship.instruments;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Turns the HTML from {@link LeaseDocument} into a PDF.
 *
 * <p>openhtmltopdf only reads strict XHTML. Our HTML is ordinary HTML5 (for
 * example, the meta tag is not closed), so jsoup reads it first and hands
 * openhtmltopdf a clean document. The renderer itself does not change.
 */
public final class PdfRenderer {

    private PdfRenderer() {
    }

    public static byte[] toPdf(String html) {
        org.w3c.dom.Document document = new W3CDom().fromJsoup(Jsoup.parse(html));

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withW3cDocument(document, "");
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render the PDF", e);
        }
    }
}

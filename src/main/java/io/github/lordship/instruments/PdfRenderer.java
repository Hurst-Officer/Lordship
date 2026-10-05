package io.github.lordship.instruments;

import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.svgsupport.BatikSVGDrawer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Calendar;
import java.util.Map;

/**
 * Turns the HTML from {@link LeaseDocument} into a PDF.
 *
 * <p>openhtmltopdf only reads strict XHTML. Our HTML is ordinary HTML5 (for
 * example, the meta tag is not closed), so jsoup reads it first and hands
 * openhtmltopdf a clean document. The renderer itself does not change.
 *
 * <p>With {@link InstrumentMetadata}, the PDF also carries what the document
 * says: the deal as an attached JSON file, and the serial and status in the
 * document info. That class explains why.
 */
public final class PdfRenderer {

    private PdfRenderer() {
    }

    public static byte[] toPdf(String html) {
        return toPdf(html, null);
    }

    /**
     * @param metadata written into the PDF, or null for none
     */
    public static byte[] toPdf(String html, InstrumentMetadata metadata) {
        org.w3c.dom.Document document = new W3CDom().fromJsoup(Jsoup.parse(html));

        PdfRendererBuilder builder = new PdfRendererBuilder();
        // Without a drawer, inline SVG is skipped and the lot map page comes
        // out blank. Batik draws it. The no-argument drawer runs no scripts
        // and fetches no external files, which is what every Batik advisory
        // is about; the two-argument constructor exists to relax that, and
        // we do not want it relaxed.
        builder.useSVGDrawer(new BatikSVGDrawer());
        builder.withW3cDocument(document, "");

        // The PDF is kept open after rendering so the metadata can be added
        // before it is saved.
        try (PdfBoxRenderer renderer = builder.buildPdfRenderer();
             PDDocument pdf = renderer.createPDFKeepOpen();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (metadata != null) {
                stamp(pdf, metadata);
            }
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render the PDF", e);
        }
    }

    /** Writes the metadata into the document info and attaches the JSON. */
    private static void stamp(PDDocument pdf, InstrumentMetadata metadata) throws IOException {
        PDDocumentInformation info = pdf.getDocumentInformation();
        info.setTitle(metadata.title());
        info.setSubject(metadata.subject());
        info.setKeywords(metadata.keywords());
        info.setCreator("Lordship");
        info.setCustomMetadataValue("LordshipFormat",
                InstrumentMetadata.FORMAT + "/" + InstrumentMetadata.FORMAT_VERSION);
        info.setCustomMetadataValue("LordshipStatus", metadata.status().name());
        if (metadata.serial() != null) {
            info.setCustomMetadataValue("LordshipSerial", metadata.serial());
        }

        PDEmbeddedFile file = new PDEmbeddedFile(pdf, new ByteArrayInputStream(metadata.json()));
        file.setSubtype("application/json");
        file.setSize(metadata.json().length);
        file.setCreationDate(Calendar.getInstance());

        PDComplexFileSpecification spec = new PDComplexFileSpecification();
        spec.setFile(InstrumentMetadata.FILE_NAME);
        spec.setFileUnicode(InstrumentMetadata.FILE_NAME);
        spec.setEmbeddedFile(file);
        spec.setEmbeddedFileUnicode(file);
        spec.setFileDescription("What this document says, for software to read");

        PDEmbeddedFilesNameTreeNode files = new PDEmbeddedFilesNameTreeNode();
        files.setNames(Map.of(InstrumentMetadata.FILE_NAME, spec));

        // Keep whatever names the renderer already wrote, such as link targets.
        PDDocumentCatalog catalog = pdf.getDocumentCatalog();
        PDDocumentNameDictionary names = catalog.getNames();
        if (names == null) {
            names = new PDDocumentNameDictionary(catalog);
        }
        names.setEmbeddedFiles(files);
        catalog.setNames(names);
    }
}

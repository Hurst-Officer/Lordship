package io.github.lordship.instruments;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import io.github.lordship.tenancy.MoveInCheckState;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The quick page check of a signed lease (Move-In's quick lane): a code printed on every page of a generated lease,
 * and the same codes read back from the signed scan within seconds of the upload.
 *
 * <p>At generate, {@link #stamp} prints a small QR code in the bottom right corner of every page, beside the serial
 * in the footer: "serial/page/pages" ("HS-41JB-5F32-RAS/7/14"). The stamp also prints three small corner marks in the
 * margins (top left, top right, bottom left; the code stands in the fourth corner), so a later check can line a
 * skewed scan up with Lordship's own page.
 *
 * <p>On upload, {@link #read} renders each page of the scan and reads the page's code. That alone tells which pages
 * are missing, which came twice, which are out of order, which belong to another lease, which are upside down
 * (kept: the page is there), which carry no code (an ID card scanned into the packet, or a page printed before the
 * codes) and which carry a code too damaged to read (a rescan helps).
 *
 * <p>The check only says whether the lease's pages are all there, once and in order. Whether each page is signed,
 * dated and filled in stays a person's call (the office's list).
 */
public final class PageCheck {

    // The resolution a scanned page is read at. A scanner's 200 or 300 dpi page reads at this without loss.
    static final int READ_DPI = 300;

    private static final float MM = 72f / 25.4f;
    // The code: 14.5 mm square, 5 mm in from the right edge and 5.5 mm up from the bottom edge, clear of what a
    // printer leaves unprinted. The footer's serial ends where the right margin begins (20 mm in), just left of the code.
    private static final float CODE_SIZE = 14.5f * MM;
    private static final float CODE_RIGHT = 5f * MM;
    private static final float CODE_BOTTOM = 5.5f * MM;
    // The corner marks: 5 mm arms, 10 mm in from each edge
    private static final float MARK_IN = 10f * MM;
    private static final float MARK_ARM = 5f * MM;

    private PageCheck() { }

    // ---- at generate ---------------------------------------------------------------------------

    /** What a page's code says: the serial, the page's number and how many pages the lease has. */
    public static String codeOf(String serial, int page, int pages) {
        return serial + "/" + page + "/" + pages;
    }

    /**
     * The same PDF with a code on every page and the corner marks. The document's other contents (metadata, fonts,
     * the pages themselves) are kept as they are.
     */
    public static byte[] stamp(byte[] pdf, String serial) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pages = document.getNumberOfPages();
            for (int i = 0; i < pages; i++) {
                PDPage page = document.getPage(i);
                PDRectangle box = page.getMediaBox();
                BitMatrix code = encode(codeOf(serial, i + 1, pages));
                try (PDPageContentStream ink = new PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    ink.setNonStrokingColor(0f, 0f, 0f);
                    drawCode(ink, code, box.getUpperRightX() - CODE_RIGHT - CODE_SIZE, box.getLowerLeftY() + CODE_BOTTOM);
                    ink.setStrokingColor(0.45f, 0.45f, 0.45f);
                    ink.setLineWidth(0.6f);
                    drawMarks(ink, box);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + pages * 4096);
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("The page codes could not be printed on the lease", e);
        }
    }

    private static BitMatrix encode(String text) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        // the white around the code is the page's own margin; a quiet zone of 2 modules is drawn as white below
        hints.put(EncodeHintType.MARGIN, 0);
        try {
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException e) {
            throw new IllegalStateException("A page code could not be made for " + text, e);
        }
    }

    // The code as filled squares, each row's dark runs drawn as one rectangle, on a white square with a quiet zone
    private static void drawCode(PDPageContentStream ink, BitMatrix code, float x, float y) throws IOException {
        int modules = code.getWidth();
        int quiet = 2;
        float module = CODE_SIZE / (modules + 2 * quiet);
        ink.setNonStrokingColor(1f, 1f, 1f);
        ink.addRect(x, y, CODE_SIZE, CODE_SIZE);
        ink.fill();
        ink.setNonStrokingColor(0f, 0f, 0f);
        for (int row = 0; row < modules; row++) {
            int col = 0;
            while (col < modules) {
                if (!code.get(col, row)) {
                    col++;
                    continue;
                }
                int start = col;
                while (col < modules && code.get(col, row)) {
                    col++;
                }
                // PDF y runs up the page, the code's rows run down
                float left = x + (quiet + start) * module;
                float bottom = y + CODE_SIZE - (quiet + row + 1) * module;
                ink.addRect(left, bottom, (col - start) * module, module);
            }
        }
        ink.fill();
    }

    private static void drawMarks(PDPageContentStream ink, PDRectangle box) throws IOException {
        float left = box.getLowerLeftX() + MARK_IN;
        float right = box.getUpperRightX() - MARK_IN;
        float bottom = box.getLowerLeftY() + MARK_IN;
        float top = box.getUpperRightY() - MARK_IN;
        // top left: an L opening down and right
        line(ink, left, top, left + MARK_ARM, top);
        line(ink, left, top, left, top - MARK_ARM);
        // top right: opening down and left
        line(ink, right, top, right - MARK_ARM, top);
        line(ink, right, top, right, top - MARK_ARM);
        // bottom left: opening up and right
        line(ink, left, bottom, left + MARK_ARM, bottom);
        line(ink, left, bottom, left, bottom + MARK_ARM);
        ink.stroke();
    }

    private static void line(PDPageContentStream ink, float x1, float y1, float x2, float y2) throws IOException {
        ink.moveTo(x1, y1);
        ink.lineTo(x2, y2);
    }

    // ---- on upload -----------------------------------------------------------------------------

    /** What one page of a scan turned out to be. */
    public enum PageResult {
        /** A page of this lease: the code names the lease's serial. */
        OK,
        /** A page of another document: the code names another serial, or is not a page code at all. */
        OTHER_LEASE,
        /** No code was found on the page (a page without one, such as an ID card, or a page printed before codes). */
        NO_CODE,
        /** A code was found and could not be read (a fold, a smudge, a poor scan): a rescan may help. */
        UNREADABLE
    }

    /**
     * One page of the scan.
     *
     * @param scanPage    the page's place in the scan, from 1
     * @param code        the code read from the page, or null
     * @param printedPage the lease's page number the code gives (for OK and OTHER_LEASE pages with a page code)
     * @param upsideDown  the code was found in the top left corner: the page was scanned upside down (kept)
     */
    public record Page(int scanPage, PageResult result, String code, Integer printedPage, boolean upsideDown) { }

    /**
     * What the check read from a scan, as kept with the upload (move_in_file.page_check) and shown to the office.
     * The lists of printed pages are the lease's page numbers; the lists of scan pages are places in the scan.
     *
     * @param serial     the lease's serial the pages were checked against
     * @param expected   how many pages the lease has (from the generated lease), or 0 when not known
     * @param scanned    how many pages the scan has
     * @param missing    printed pages not found in the scan
     * @param twice      printed pages found more than once
     * @param outOfOrder scan pages whose page number comes before a page scanned ahead of them
     * @param otherLease scan pages that belong to another document
     * @param upsideDown scan pages scanned upside down
     * @param noCode     scan pages with no code
     * @param unreadable scan pages whose code could not be read
     * @param state      PASSED (every page once and in order, nothing from another document), UNSURE (pages are
     *                   missing, and unreadable or uncoded pages could be them: a rescan or a person decides) or
     *                   FAILED
     */
    public record Reading(String serial, int expected, int scanned, List<Page> pages, List<Integer> missing,
                          List<Integer> twice, List<Integer> outOfOrder, List<Integer> otherLease,
                          List<Integer> upsideDown, List<Integer> noCode, List<Integer> unreadable,
                          MoveInCheckState state) { }

    /**
     * Reads every page's code from a scanned PDF and checks the pages against the lease.
     *
     * @param scan     the uploaded PDF
     * @param serial   the lease's serial
     * @param expected how many pages the generated lease has; 0 when not known (the codes then say)
     * @throws IOException when the upload is not a PDF that can be opened
     */
    public static Reading read(byte[] scan, String serial, int expected) throws IOException {
        List<Page> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(scan)) {
            PDFRenderer renderer = new PDFRenderer(document);
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, READ_DPI, ImageType.GRAY);
                pages.add(pageOf(i + 1, readCode(image), serial));
            }
        }
        return judge(serial, expected, pages);
    }

    /** The reading of a scan whose pages have been read: what is missing, twice, out of order, and the state. */
    static Reading judge(String serial, int expected, List<Page> pages) {
        int total = expected;
        if (total <= 0) {
            // not known from the lease: the largest page count a code of this lease gives
            total = pages.stream()
                    .filter(page -> page.result() == PageResult.OK)
                    .map(page -> pagesOf(page.code()))
                    .flatMap(Optional::stream)
                    .max(Integer::compare)
                    .orElse(0);
        }

        Map<Integer, Integer> seen = new TreeMap<>();
        List<Integer> outOfOrder = new ArrayList<>();
        List<Integer> otherLease = new ArrayList<>();
        List<Integer> upsideDown = new ArrayList<>();
        List<Integer> noCode = new ArrayList<>();
        List<Integer> unreadable = new ArrayList<>();
        int last = 0;
        for (Page page : pages) {
            if (page.upsideDown()) {
                upsideDown.add(page.scanPage());
            }
            switch (page.result()) {
                case OK -> {
                    int printed = page.printedPage();
                    seen.merge(printed, 1, Integer::sum);
                    if (printed < last) {
                        outOfOrder.add(page.scanPage());
                    }
                    last = Math.max(last, printed);
                }
                case OTHER_LEASE -> otherLease.add(page.scanPage());
                case NO_CODE -> noCode.add(page.scanPage());
                case UNREADABLE -> unreadable.add(page.scanPage());
            }
        }
        List<Integer> missing = new ArrayList<>();
        for (int printed = 1; printed <= total; printed++) {
            if (!seen.containsKey(printed)) {
                missing.add(printed);
            }
        }
        List<Integer> twice = seen.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();

        MoveInCheckState state;
        boolean wrong = !twice.isEmpty() || !outOfOrder.isEmpty() || !otherLease.isEmpty();
        if (total == 0) {
            // no page carries this lease's code: a lease printed before the codes, or a scan nothing can be read from
            state = MoveInCheckState.UNSURE;
        } else if (!wrong && missing.isEmpty()) {
            state = MoveInCheckState.PASSED;
        } else if (!wrong && noCode.size() + unreadable.size() >= missing.size()) {
            // every missing page could be one that carries no readable code: a rescan, or a person, decides
            state = MoveInCheckState.UNSURE;
        } else {
            state = MoveInCheckState.FAILED;
        }
        return new Reading(serial, total, pages.size(), List.copyOf(pages), missing, twice, outOfOrder, otherLease,
                upsideDown, noCode, unreadable, state);
    }

    // One page from the code read on the page (or what kept the code from being read)
    private static Page pageOf(int scanPage, Read read, String serial) {
        if (read.result() == null) {
            return new Page(scanPage, read.damaged() ? PageResult.UNREADABLE : PageResult.NO_CODE, null, null, false);
        }
        String text = read.result().getText();
        String[] parts = text.split("/");
        Integer printed = parts.length == 3 ? number(parts[1]) : null;
        boolean ours = parts.length == 3 && parts[0].equals(serial) && printed != null && number(parts[2]) != null;
        return new Page(scanPage, ours ? PageResult.OK : PageResult.OTHER_LEASE, text, printed, read.upsideDown());
    }

    private static Optional<Integer> pagesOf(String code) {
        String[] parts = code == null ? new String[0] : code.split("/");
        return parts.length == 3 ? Optional.ofNullable(number(parts[2])) : Optional.empty();
    }

    private static Integer number(String text) {
        try {
            int n = Integer.parseInt(text.trim());
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // A code read from a page: the result, or null with damaged telling a code too damaged from no code at all
    private record Read(Result result, boolean damaged, boolean upsideDown) { }

    // Reads the page's code: where the code is printed first (the bottom right corner), then the top left corner
    // (a page scanned upside down), then the whole page (a page scanned turned, or cropped)
    private static Read readCode(BufferedImage page) {
        int w = page.getWidth();
        int h = page.getHeight();
        boolean damaged = false;
        int[][] regions = {
                {w / 2, h / 2, w - w / 2, h - h / 2},   // bottom right
                {0, 0, w / 2, h / 2},                   // top left
                {0, 0, w, h},                           // the whole page
        };
        for (int[] region : regions) {
            BufferedImage part = page.getSubimage(region[0], region[1], region[2], region[3]);
            // as scanned first, then with the specks of a dusty scanner glass smoothed away
            for (boolean smoothed : new boolean[] {false, true}) {
                try {
                    Result result = decode(smoothed ? smoothed(part) : part);
                    float[] centre = centreOf(result.getResultPoints());
                    boolean upsideDown = centre != null
                            && region[0] + centre[0] < w / 2f
                            && region[1] + centre[1] < h / 2f;
                    return new Read(result, false, upsideDown);
                } catch (ChecksumException | FormatException e) {
                    damaged = true;
                } catch (NotFoundException e) {
                    // nothing found this way: try the next
                }
            }
        }
        return new Read(null, damaged, false);
    }

    // The image with each pixel set to the middle brightness of the pixel and the pixel's eight neighbours (a median), which
    // takes out single dark or light specks and keeps the edges of the code's squares
    private static BufferedImage smoothed(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] in = new GrayImage(image).getMatrix();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        byte[] result = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        int[] window = new int[9];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = Math.min(h - 1, Math.max(0, y + dy));
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = Math.min(w - 1, Math.max(0, x + dx));
                        window[n++] = in[yy * w + xx] & 0xff;
                    }
                }
                java.util.Arrays.sort(window);
                result[y * w + x] = (byte) window[4];
            }
        }
        return out;
    }

    private static Result decode(BufferedImage image) throws NotFoundException, ChecksumException, FormatException {
        Map<DecodeHintType, Object> hints = new HashMap<>();
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE));
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new GrayImage(image)));
        return new QRCodeReader().decode(bitmap, hints);
    }

    private static float[] centreOf(ResultPoint[] points) {
        if (points == null || points.length == 0) {
            return null;
        }
        float x = 0;
        float y = 0;
        for (ResultPoint point : points) {
            x += point.getX();
            y += point.getY();
        }
        return new float[] {x / points.length, y / points.length};
    }

    // A grayscale page as ZXing reads one: one byte of brightness for each pixel
    private static final class GrayImage extends LuminanceSource {
        private final byte[] pixels;

        GrayImage(BufferedImage image) {
            super(image.getWidth(), image.getHeight());
            BufferedImage gray = image;
            if (image.getType() != BufferedImage.TYPE_BYTE_GRAY || image.getRaster().getParent() != null) {
                // a part of a larger page shares the page's pixels: copy the part into a gray image of the part's own
                gray = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
                gray.getGraphics().drawImage(image, 0, 0, null);
            }
            this.pixels = ((DataBufferByte) gray.getRaster().getDataBuffer()).getData();
        }

        @Override
        public byte[] getRow(int y, byte[] row) {
            int width = getWidth();
            if (row == null || row.length < width) {
                row = new byte[width];
            }
            System.arraycopy(pixels, y * width, row, 0, width);
            return row;
        }

        @Override
        public byte[] getMatrix() {
            return pixels;
        }
    }
}

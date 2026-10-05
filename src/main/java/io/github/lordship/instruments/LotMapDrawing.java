package io.github.lordship.instruments;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Draws the lot map page: one park, one lot highlighted, as SVG.
 *
 * <p>RCW 59.20.060(2)(k) requires a lease to show where the tenant's space is
 * in relation to the other spaces. That is what this page is.
 *
 * <p>The map fills the whole sheet, edge to edge. A park is rarely the shape of
 * a page, so the drawing is turned to whatever angle fits the most of it, and
 * the north arrow turns with it. What still does not fit runs off the edge
 * behind a fade.
 *
 * <p>With imagery, a satellite photo sits under the drawing. The photo is
 * turned and cut to the sheet here, then set as the background of a div
 * around the SVG. It is not put inside the SVG: Batik would have to load it,
 * and the PDF renderer's Batik is locked down so it loads nothing. The PDF
 * engine draws the background itself and keeps the JPEG as a JPEG.
 *
 * <p>Pure. Coordinates in, markup out. No database, no Spring, no file system,
 * so the drawing can be unit tested the way a number format is. The photo is
 * fetched by whoever passes in the {@link Imagery}.
 *
 * <p>Nothing here reads a clause body. The only text on the page is the park
 * name, lot numbers, road names and the credit line, and every one of them is
 * escaped on the way in.
 */
public final class LotMapDrawing {

    private LotMapDrawing() {}

    /**
     * The map sheet, in millimetres: a letter page less the lease's margins.
     *
     * <p>Not the paper. The map cannot run to the edge of the sheet, because the
     * PDF engine keeps a page's margins even where the stylesheet asks a named
     * page to drop them -- PdfPageRulesTest pins that down. Office printers
     * cannot print to the edge either, so little is lost.
     *
     * <p>A few millimetres under the arithmetic. Letter less 22mm top and 20mm
     * elsewhere is 175.9 x 237.4, but a block that size spills onto a second
     * page: the engine's own rounding leaves the content box a little smaller
     * than the margins say. PdfPageRulesTest holds the size that actually fits,
     * and the two have to be changed together.
     */
    private static final double SHEET_WIDTH_MM = 170.9;
    private static final double SHEET_HEIGHT_MM = 230.4;

    /** How big a road name prints. */
    private static final double ROAD_LABEL_MM = 2.3;

    /** How far in from the edge the name, scale bar and credits sit. */
    private static final double MARGIN_MM = 9;

    /** A lot number smaller than this cannot be read, so it is not drawn. */
    private static final double SMALLEST_LABEL_MM = 1.7;

    /** The biggest a lot number is drawn, and the size the tenant's own gets. */
    private static final double LABEL_MM = 3.2;
    private static final double TENANT_LABEL_MM = 4.2;

    /**
     * A typical lot this wide on paper is readable. A park that cannot manage
     * it whole is drawn at this size around the tenant's lot instead, and the
     * rest runs off the page.
     */
    private static final double READABLE_LOT_WIDTH_MM = 9;

    /** How far the fade reaches in from an edge the park runs past. */
    private static final double FADE_MM = 14;

    private static final double METRES_PER_DEGREE_LAT = 110574;
    private static final double FEET_PER_METRE = 3.28084;

    /** How sharp the photo prints. 200 dpi is about what the imagery holds. */
    private static final int PHOTO_DPI = 200;

    /** JPEG quality for the photo. Lower makes a smaller PDF and a blurrier photo. */
    private static final float PHOTO_QUALITY = 0.82f;

    /**
     * How much white is laid over the photo, from 0 to 1. A little makes the
     * lines and numbers easier to read and uses less ink.
     */
    private static final float PHOTO_WASH = 0.15f;

    /** Where the satellite photo comes from. Tests pass their own. */
    public interface Imagery {

        /** No photo. The map prints on white, as it always has. */
        Imagery NONE = (west, south, east, north, metersPerPixel) -> Optional.empty();

        /**
         * A north-up photo covering at least this box of [lng, lat], or empty
         * when there is none.
         *
         * @param metersPerPixel the detail the page wants
         */
        Optional<Photo> photo(double west, double south, double east, double north, double metersPerPixel);
    }

    /**
     * A north-up photo, the [lng, lat] of its edges, and the credit line its
     * license requires.
     */
    public record Photo(BufferedImage image, double west, double south, double east, double north,
                        String credit) {}

    /** One lot: its number and its outline as [lng, lat] pairs. */
    public record Lot(String number, double[][] ring) {}

    /** A road or a stream. Width is the real width on the ground, in metres. */
    public record Line(String name, double widthMetres, double[][] points) {}

    /** Water, the park boundary, or a home footprint. */
    public record Area(double[][] ring) {}

    /**
     * Everything the page draws.
     *
     * @param tenantLotNumber which lot to highlight; it must be in {@code lots}
     * @param home the tenant's own home footprint, or null
     * @param boundary the park outline, or null outside Washington
     * @param credits the attribution lines the data's licence requires
     */
    public record Park(
            String name,
            String tenantLotNumber,
            List<Lot> lots,
            List<Line> roads,
            List<Area> water,
            Area home,
            Area boundary,
            List<String> credits,
            LocalDate drawnOn
    ) {
        public Park {
            lots = List.copyOf(lots);
            roads = List.copyOf(roads);
            water = List.copyOf(water);
            credits = List.copyOf(credits);
        }
    }

    /**
     * The page, as one SVG element, on white.
     *
     * @throws IllegalArgumentException when the tenant's lot is not in the park
     */
    public static String draw(Park park) {
        return draw(park, Imagery.NONE);
    }

    /**
     * The page over a satellite photo. When the imagery has no photo, this is
     * the same as {@link #draw(Park)}: one SVG element on white.
     *
     * @throws IllegalArgumentException when the tenant's lot is not in the park
     */
    public static String draw(Park park, Imagery imagery) {
        Lot tenant = park.lots().stream()
                .filter(lot -> lot.number().equals(park.tenantLotNumber()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "lot " + park.tenantLotNumber() + " is not in this park"));

        // Metres from the middle of the park, so the numbers stay small and a
        // rounding error stays under a millimetre.
        double[] middle = middleOf(park.lots());
        Projection to = new Projection(middle[0], middle[1]);

        double turn = bestTurn(park.lots(), to);
        Frame whole = frameOf(park.lots(), to, turn).grownBy(4).fittedToPage();

        Frame view = whole;
        boolean cropped = false;
        if (medianLotWidthMm(park.lots(), to, turn, whole) < READABLE_LOT_WIDTH_MM) {
            view = readableViewAround(park.lots(), tenant, to, turn);
            cropped = true;
        }

        Page page = new Page(to, turn, view);
        Optional<Photo> photo = photoFor(page, imagery);
        boolean onPhoto = photo.isPresent();

        List<String> credits = new ArrayList<>(park.credits());
        if (onPhoto && photo.get().credit() != null && !photo.get().credit().isBlank()) {
            credits.add(photo.get().credit());
        }

        StringBuilder out = new StringBuilder(16384);

        // One font for the whole drawing. Without it each renderer picks its
        // own default and the same lease prints differently on two machines.
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"lot-map\" ")
                .append("font-family=\"Helvetica, Arial, sans-serif\" ")
                .append("width=\"").append(mm(SHEET_WIDTH_MM)).append("mm\" ")
                .append("height=\"").append(mm(SHEET_HEIGHT_MM)).append("mm\" ")
                .append("viewBox=\"0 0 ").append(mm(SHEET_WIDTH_MM)).append(' ')
                .append(mm(SHEET_HEIGHT_MM)).append("\">");

        // On a photo the SVG stays see-through so the photo shows.
        if (!onPhoto) {
            out.append("<rect x=\"0\" y=\"0\" width=\"").append(mm(SHEET_WIDTH_MM))
                    .append("\" height=\"").append(mm(SHEET_HEIGHT_MM)).append("\" fill=\"#ffffff\"/>");
        }

        if (cropped) {
            out.append(fadeDefs());
        }

        // The photo already shows the water and the roads. Drawing them over
        // it would only hide the real thing, so only the road names are kept.
        if (onPhoto) {
            drawRoadNames(out, park, page, true);
        } else {
            drawWater(out, park, page);
            drawRoads(out, park, page);
        }
        drawBoundary(out, park, page, onPhoto);
        drawLots(out, park, tenant, page, onPhoto);
        drawHome(out, park, tenant, page, onPhoto);
        drawLotNumbers(out, park, tenant, page, onPhoto);

        if (cropped) {
            drawFade(out);
        }
        double[] arrow = arrowAt(park, page, tenant);
        drawParkName(out, park, page, arrow);
        drawFurniture(out, park, page, turn, arrow, credits, onPhoto);

        out.append("</svg>");

        if (!onPhoto) {
            return out.toString();
        }
        return "<div class=\"lot-map-photo\" style=\"width:" + mm(SHEET_WIDTH_MM) + "mm;height:"
                + mm(SHEET_HEIGHT_MM) + "mm;background-image:url('data:image/jpeg;base64,"
                + photoOnSheet(photo.get(), page) + "');background-size:" + mm(SHEET_WIDTH_MM) + "mm "
                + mm(SHEET_HEIGHT_MM) + "mm;background-repeat:no-repeat\">" + out + "</div>";
    }

    // ---- the photo -----------------------------------------------------------

    /** Asks the imagery for a photo of the ground the sheet shows. */
    private static Optional<Photo> photoFor(Page page, Imagery imagery) {
        if (imagery == null) {
            return Optional.empty();
        }

        // The sheet is turned, so its corners are found one by one and the
        // photo is asked for the box around all four.
        double[][] corners = {
                page.lngLat(0, 0),
                page.lngLat(SHEET_WIDTH_MM, 0),
                page.lngLat(0, SHEET_HEIGHT_MM),
                page.lngLat(SHEET_WIDTH_MM, SHEET_HEIGHT_MM)};
        double west = Double.MAX_VALUE;
        double east = -Double.MAX_VALUE;
        double south = Double.MAX_VALUE;
        double north = -Double.MAX_VALUE;
        for (double[] corner : corners) {
            west = Math.min(west, corner[0]);
            east = Math.max(east, corner[0]);
            south = Math.min(south, corner[1]);
            north = Math.max(north, corner[1]);
        }

        double mmPerPixel = 25.4 / PHOTO_DPI;
        double metersPerPixel = mmPerPixel / page.view().mmPerMetre();
        return imagery.photo(west, south, east, north, metersPerPixel);
    }

    /**
     * The photo turned and cut to the sheet, as base64 JPEG.
     *
     * <p>Latitude is treated as evenly spaced down the photo. Web imagery is
     * not quite, but across a park the difference is a few centimeters.
     */
    private static String photoOnSheet(Photo photo, Page page) {
        int wide = (int) Math.round(SHEET_WIDTH_MM / 25.4 * PHOTO_DPI);
        int tall = (int) Math.round(SHEET_HEIGHT_MM / 25.4 * PHOTO_DPI);
        double pixelsPerMm = wide / SHEET_WIDTH_MM;

        // Where three corners of the photo land on the sheet, in sheet pixels.
        // Three corners are enough to place a picture that is only moved,
        // turned and scaled.
        BufferedImage source = photo.image();
        double topLeftX = page.x(photo.west(), photo.north()) * pixelsPerMm;
        double topLeftY = page.y(photo.west(), photo.north()) * pixelsPerMm;
        double topRightX = page.x(photo.east(), photo.north()) * pixelsPerMm;
        double topRightY = page.y(photo.east(), photo.north()) * pixelsPerMm;
        double bottomLeftX = page.x(photo.west(), photo.south()) * pixelsPerMm;
        double bottomLeftY = page.y(photo.west(), photo.south()) * pixelsPerMm;

        AffineTransform place = new AffineTransform(
                (topRightX - topLeftX) / source.getWidth(),
                (topRightY - topLeftY) / source.getWidth(),
                (bottomLeftX - topLeftX) / source.getHeight(),
                (bottomLeftY - topLeftY) / source.getHeight(),
                topLeftX,
                topLeftY);

        BufferedImage sheet = new BufferedImage(wide, tall, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, wide, tall);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, place, null);

            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, PHOTO_WASH));
            g.fillRect(0, 0, wide, tall);
        } finally {
            g.dispose();
        }
        return Base64.getEncoder().encodeToString(jpeg(sheet));
    }

    private static byte[] jpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam settings = writer.getDefaultWriteParam();
        settings.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        settings.setCompressionQuality(PHOTO_QUALITY);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), settings);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the map photo", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    // ---- turning the map -----------------------------------------------------

    /**
     * The angle that fits the most park on the page.
     *
     * <p>A park strung along a diagonal road wastes half the sheet drawn
     * north-up. Turning it costs nothing: every degree is tried, and the one
     * whose bounding box fills the page best wins. North stops being up, which
     * is what the north arrow is for.
     */
    static double bestTurn(List<Lot> lots, Projection to) {
        double best = 0;
        double bestScale = 0;
        for (int degrees = 0; degrees < 180; degrees++) {
            double turn = Math.toRadians(degrees);
            Frame frame = frameOf(lots, to, turn);
            double scale = Math.min(SHEET_WIDTH_MM / frame.width(), SHEET_HEIGHT_MM / frame.height());
            if (scale > bestScale) {
                bestScale = scale;
                best = turn;
            }
        }
        return best;
    }

    // ---- the drawing itself --------------------------------------------------

    /** Turns [lng, lat] into millimetres on the page, through the turn. */
    private record Page(Projection to, double turn, Frame view) {
        double x(double lng, double lat) {
            return (turned(to, turn, lng, lat)[0] - view.minX()) * view.mmPerMetre();
        }

        double y(double lng, double lat) {
            return (turned(to, turn, lng, lat)[1] - view.minY()) * view.mmPerMetre();
        }

        /** The other way: a point on the page, in mm, back to [lng, lat]. */
        double[] lngLat(double xMm, double yMm) {
            double turnedX = xMm / view.mmPerMetre() + view.minX();
            double turnedY = yMm / view.mmPerMetre() + view.minY();
            double cos = Math.cos(turn);
            double sin = Math.sin(turn);
            double x = turnedX * cos + turnedY * sin;
            double y = -turnedX * sin + turnedY * cos;
            return to.lngLat(x, y);
        }

        String path(double[][] points, boolean close) {
            StringBuilder d = new StringBuilder(points.length * 12);
            for (int i = 0; i < points.length; i++) {
                d.append(i == 0 ? 'M' : 'L')
                        .append(mm(x(points[i][0], points[i][1]))).append(' ')
                        .append(mm(y(points[i][0], points[i][1])));
            }
            if (close) {
                d.append('Z');
            }
            return d.toString();
        }
    }

    private static void drawWater(StringBuilder out, Park park, Page page) {
        for (Area area : park.water()) {
            out.append("<path d=\"").append(page.path(area.ring(), true))
                    .append("\" fill=\"#dbe7f0\" stroke=\"#9db6c8\" stroke-width=\"0.2\"/>");
        }
    }

    private static void drawRoads(StringBuilder out, Park park, Page page) {
        // Casing first, then the fill, which is what makes a road read as a road
        // rather than as a thick line.
        for (Line road : park.roads()) {
            out.append("<path d=\"").append(page.path(road.points(), false))
                    .append("\" fill=\"none\" stroke=\"#c4c4c4\" stroke-width=\"")
                    .append(mm(roadWidthMm(road, page) + 0.5))
                    .append("\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>");
        }
        for (Line road : park.roads()) {
            out.append("<path d=\"").append(page.path(road.points(), false))
                    .append("\" fill=\"none\" stroke=\"#ffffff\" stroke-width=\"")
                    .append(mm(roadWidthMm(road, page)))
                    .append("\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>");
        }
        drawRoadNames(out, park, page, false);
    }

    private static double roadWidthMm(Line road, Page page) {
        return Math.max(road.widthMetres(), 2) * page.view().mmPerMetre();
    }

    /**
     * Each road name once, laid along the longest straight run of that road.
     *
     * <p>Ordinary rotated text, not a textPath. A textPath points at a path by
     * id, and that reference does not survive the trip into the PDF: the
     * drawing travels inside an HTML page, which loses the xlink namespace, and
     * Batik then looks up an empty id and throws. Nothing in this drawing
     * refers to anything else now, which is the simplest way to keep it so.
     */
    private static void drawRoadNames(StringBuilder out, Park park, Page page, boolean onPhoto) {
        List<String> done = new ArrayList<>();
        for (Line road : park.roads()) {
            if (road.name() == null || road.name().isBlank() || done.contains(road.name())) {
                continue;
            }

            // The longest straight run of the road holds the most letters.
            double[][] points = road.points();
            double[] longest = null;
            double span = 0;
            for (int i = 0; i + 1 < points.length; i++) {
                double x1 = page.x(points[i][0], points[i][1]);
                double y1 = page.y(points[i][0], points[i][1]);
                double x2 = page.x(points[i + 1][0], points[i + 1][1]);
                double y2 = page.y(points[i + 1][0], points[i + 1][1]);
                double length = Math.hypot(x2 - x1, y2 - y1);
                if (length > span) {
                    span = length;
                    longest = new double[] {x1, y1, x2, y2};
                }
            }

            // No room for the name is better than a name running off the road.
            if (longest == null || span < road.name().length() * ROAD_LABEL_MM * 0.58) {
                continue;
            }
            done.add(road.name());

            double angle = Math.toDegrees(Math.atan2(longest[3] - longest[1], longest[2] - longest[0]));
            if (angle > 90) {
                angle -= 180;   // otherwise the name prints upside down
            }
            if (angle < -90) {
                angle += 180;
            }
            double midX = (longest[0] + longest[2]) / 2;
            double midY = (longest[1] + longest[3]) / 2;

            String place = "x=\"" + mm(midX) + "\" y=\"" + mm(midY) + "\" transform=\"rotate("
                    + mm(angle) + ' ' + mm(midX) + ' ' + mm(midY) + ")\""
                    + " text-anchor=\"middle\" dy=\"-0.9\" font-size=\"" + mm(ROAD_LABEL_MM) + "\"";
            if (onPhoto) {
                drawWithHalo(out, place, "", road.name());
            } else {
                out.append("<text ").append(place).append(" fill=\"#6b6b6b\">")
                        .append(escape(road.name())).append("</text>");
            }
        }
    }

    /**
     * White text with a dark outline under it, so it reads on any part of a
     * photo.
     *
     * <p>Drawn as two text elements, outline first. Batik does not know
     * paint-order, so one element with a stroke would put the outline over
     * the letters.
     */
    private static void drawWithHalo(StringBuilder out, String place, String weight, String text) {
        out.append("<text ").append(place).append(weight)
                .append(" fill=\"none\" stroke=\"#000000\" stroke-opacity=\"0.75\" stroke-width=\"0.7\"")
                .append(" stroke-linejoin=\"round\">").append(escape(text)).append("</text>")
                .append("<text ").append(place).append(weight)
                .append(" fill=\"#ffffff\">").append(escape(text)).append("</text>");
    }

    private static void drawBoundary(StringBuilder out, Park park, Page page, boolean onPhoto) {
        if (park.boundary() == null) {
            return;
        }
        String d = page.path(park.boundary().ring(), true);
        if (onPhoto) {
            // A dark line under the white dashes keeps them visible on grass and on gravel.
            out.append("<path d=\"").append(d)
                    .append("\" fill=\"none\" stroke=\"#000000\" stroke-opacity=\"0.5\" stroke-width=\"0.9\"/>")
                    .append("<path d=\"").append(d)
                    .append("\" fill=\"none\" stroke=\"#ffffff\" stroke-width=\"0.5\" stroke-dasharray=\"2.5 1.8\"/>");
            return;
        }
        out.append("<path d=\"").append(d)
                .append("\" fill=\"none\" stroke=\"#8a8a8a\" stroke-width=\"0.5\" stroke-dasharray=\"2.5 1.8\"/>");
    }

    private static void drawLots(StringBuilder out, Park park, Lot tenant, Page page, boolean onPhoto) {
        if (onPhoto) {
            drawLotsOnPhoto(out, park, tenant, page);
            return;
        }
        for (Lot lot : park.lots()) {
            boolean isTenant = lot == tenant;
            out.append("<path d=\"").append(page.path(lot.ring(), true))
                    .append("\" fill=\"").append(isTenant ? "#f0dca8" : "#fbfbf9")
                    .append("\" stroke=\"#333\" stroke-width=\"")
                    .append(isTenant ? "0.8" : "0.25").append("\"/>");
        }
    }

    /**
     * Lots as lines only, so the photo shows through. Thin white lines for the
     * other lots. The tenant's lot gets a light yellow tint and a thick yellow
     * line with a dark edge, drawn last so nothing crosses it.
     */
    private static void drawLotsOnPhoto(StringBuilder out, Park park, Lot tenant, Page page) {
        for (Lot lot : park.lots()) {
            if (lot == tenant) {
                continue;
            }
            out.append("<path d=\"").append(page.path(lot.ring(), true))
                    .append("\" fill=\"none\" stroke=\"#ffffff\" stroke-opacity=\"0.9\" stroke-width=\"0.3\"/>");
        }
        String d = page.path(tenant.ring(), true);
        out.append("<path d=\"").append(d)
                .append("\" fill=\"#ffd84d\" fill-opacity=\"0.3\" stroke=\"#000000\" stroke-width=\"1.5\"")
                .append(" stroke-linejoin=\"round\"/>")
                .append("<path d=\"").append(d)
                .append("\" fill=\"none\" stroke=\"#ffd84d\" stroke-width=\"0.9\" stroke-linejoin=\"round\"/>");
    }

    /**
     * The tenant's home, cut to the edges of their lot.
     *
     * <p>The footprint comes from OpenStreetMap and the lot from the plat, and
     * the two rarely line up exactly. Cutting the home to the lot keeps it from
     * spilling onto a neighbor's lot.
     *
     * <p>The fill is darker than the lot's highlight so the home stands out,
     * but light enough that the bold lot number on top stays readable.
     */
    private static void drawHome(StringBuilder out, Park park, Lot tenant, Page page, boolean onPhoto) {
        if (park.home() == null) {
            return;
        }
        List<double[][]> pieces = insideOf(park.home().ring(), tenant.ring());
        if (pieces.isEmpty()) {
            return;
        }
        StringBuilder d = new StringBuilder();
        for (double[][] piece : pieces) {
            d.append(page.path(piece, true));
        }
        // On a photo the real roof is already there, so the footprint is only
        // a light outline around it.
        if (onPhoto) {
            out.append("<path d=\"").append(d)
                    .append("\" fill=\"#ffffff\" fill-opacity=\"0.2\" stroke=\"#ffffff\" stroke-width=\"0.45\"/>");
            return;
        }
        out.append("<path d=\"").append(d)
                .append("\" fill=\"#a8946a\" stroke=\"#3d3528\" stroke-width=\"0.45\"/>");
    }

    /**
     * The parts of {@code ring} that fall inside {@code within}, as rings of
     * [lng, lat]. Empty when the two do not overlap.
     *
     * <p>The math is done in small numbers around {@code within}'s first
     * point, about a tenth of a metre per unit, so the cut stays exact.
     */
    static List<double[][]> insideOf(double[][] ring, double[][] within) {
        double originLng = within[0][0];
        double originLat = within[0][1];
        double scale = 1_000_000;

        java.awt.geom.Area kept = new java.awt.geom.Area(shapeOf(ring, originLng, originLat, scale));
        kept.intersect(new java.awt.geom.Area(shapeOf(within, originLng, originLat, scale)));

        List<double[][]> rings = new ArrayList<>();
        List<double[]> current = new ArrayList<>();
        double[] point = new double[6];
        for (PathIterator it = kept.getPathIterator(null); !it.isDone(); it.next()) {
            int segment = it.currentSegment(point);
            if (segment == PathIterator.SEG_MOVETO) {
                current = new ArrayList<>();
            }
            if (segment == PathIterator.SEG_MOVETO || segment == PathIterator.SEG_LINETO) {
                current.add(new double[] {
                        originLng + point[0] / scale,
                        originLat + point[1] / scale});
            }
            if (segment == PathIterator.SEG_CLOSE && current.size() >= 3) {
                rings.add(current.toArray(new double[0][]));
            }
        }
        return rings;
    }

    private static Path2D shapeOf(double[][] ring, double originLng, double originLat, double scale) {
        Path2D.Double shape = new Path2D.Double();
        for (int i = 0; i < ring.length; i++) {
            double x = (ring[i][0] - originLng) * scale;
            double y = (ring[i][1] - originLat) * scale;
            if (i == 0) {
                shape.moveTo(x, y);
            } else {
                shape.lineTo(x, y);
            }
        }
        shape.closePath();
        return shape;
    }

    /**
     * The lot numbers, each sized to the lot it belongs to.
     *
     * <p>A number wider than its lot is worse than no number: at park scale the
     * dense rows turn into a smudge of overlapping text. A lot that cannot hold
     * its number keeps its outline and loses the label. The tenant's own lot
     * always keeps it, since that is the one the page is about.
     */
    private static void drawLotNumbers(StringBuilder out, Park park, Lot tenant, Page page, boolean onPhoto) {
        for (Lot lot : park.lots()) {
            boolean isTenant = lot == tenant;
            double size = labelSizeMm(lot, page, isTenant);
            if (size <= 0) {
                continue;
            }
            double[] at = labelPoint(lot.ring());
            String place = "x=\"" + mm(page.x(at[0], at[1])) + "\" y=\""
                    + mm(page.y(at[0], at[1]) + size * 0.35)
                    + "\" text-anchor=\"middle\" font-size=\"" + mm(size) + "\"";
            String weight = " font-weight=\"" + (isTenant ? "bold" : "normal") + "\"";
            if (onPhoto) {
                drawWithHalo(out, place, weight, lot.number());
            } else {
                out.append("<text ").append(place).append(weight)
                        .append(" fill=\"#222\">").append(escape(lot.number())).append("</text>");
            }
        }
    }

    /** How big this lot's number can be drawn, or 0 when it cannot be. */
    private static double labelSizeMm(Lot lot, Page page, boolean isTenant) {
        double[][] box = pageBoxOf(lot, page);
        double wide = box[1][0] - box[0][0];
        double tall = box[1][1] - box[0][1];

        int characters = Math.max(lot.number().length(), 1);
        // About 0.58em per character for the digits and letters a lot number
        // is made of, and never taller than the lot itself.
        double bySides = Math.min(wide / (characters * 0.58), tall * 0.8);
        double size = Math.min(bySides, isTenant ? TENANT_LABEL_MM : LABEL_MM);

        if (isTenant) {
            return Math.max(size, SMALLEST_LABEL_MM);
        }
        return size < SMALLEST_LABEL_MM ? 0 : size;
    }

    /** The gradients that soften the edges the park runs past. */
    private static String fadeDefs() {
        String stops = "<stop offset=\"0\" stop-color=\"#ffffff\" stop-opacity=\"1\"/>"
                + "<stop offset=\"1\" stop-color=\"#ffffff\" stop-opacity=\"0\"/>";
        return "<defs>"
                + "<linearGradient id=\"fade-left\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"0\">" + stops + "</linearGradient>"
                + "<linearGradient id=\"fade-right\" x1=\"1\" y1=\"0\" x2=\"0\" y2=\"0\">" + stops + "</linearGradient>"
                + "<linearGradient id=\"fade-top\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">" + stops + "</linearGradient>"
                + "<linearGradient id=\"fade-bottom\" x1=\"0\" y1=\"1\" x2=\"0\" y2=\"0\">" + stops + "</linearGradient>"
                + "</defs>";
    }

    /**
     * White, fading to nothing, along each edge.
     *
     * <p>A park too big for the page has to be cut somewhere. A hard cut looks
     * like a mistake; a fade looks like the edge of a drawing.
     */
    private static void drawFade(StringBuilder out) {
        out.append("<rect x=\"0\" y=\"0\" width=\"").append(mm(FADE_MM))
                .append("\" height=\"").append(mm(SHEET_HEIGHT_MM)).append("\" fill=\"url(#fade-left)\"/>")
                .append("<rect x=\"").append(mm(SHEET_WIDTH_MM - FADE_MM)).append("\" y=\"0\" width=\"")
                .append(mm(FADE_MM)).append("\" height=\"").append(mm(SHEET_HEIGHT_MM))
                .append("\" fill=\"url(#fade-right)\"/>")
                .append("<rect x=\"0\" y=\"0\" width=\"").append(mm(SHEET_WIDTH_MM))
                .append("\" height=\"").append(mm(FADE_MM)).append("\" fill=\"url(#fade-top)\"/>")
                .append("<rect x=\"0\" y=\"").append(mm(SHEET_HEIGHT_MM - FADE_MM))
                .append("\" width=\"").append(mm(SHEET_WIDTH_MM)).append("\" height=\"").append(mm(FADE_MM))
                .append("\" fill=\"url(#fade-bottom)\"/>");
    }

    /** Name sizes to try, largest first. */
    private static final double[] PARK_NAME_SIZES_MM = {6, 5, 4};

    /** How far apart the spots tried for the name are, in mm. */
    private static final double PARK_NAME_STEP_MM = 2;

    /**
     * The community's name, on a white plate, outside the park.
     *
     * <p>The plate never goes inside the park's outline (a band wrapped tight
     * around all the lots), so it can never cover a lot, a street inside the
     * park, or a street name. It also keeps clear of the north arrow and the
     * strip along the bottom (scale bar, caption, credits).
     *
     * <p>It looks for the highest free spot across the page, at 6, then 5,
     * then 4 mm. If there is none, the name is left off. The lease names the
     * park elsewhere.
     */
    private static void drawParkName(StringBuilder out, Park park, Page page, double[] arrow) {
        if (park.name() == null || park.name().isBlank()) {
            return;
        }
        Path2D parkOutline = parkOutlineOnPage(park, page);
        List<Rectangle2D> keepClear = List.of(
                arrowBox(arrow),
                new Rectangle2D.Double(0, SHEET_HEIGHT_MM - MARGIN_MM - 12, SHEET_WIDTH_MM, MARGIN_MM + 12));

        for (double size : PARK_NAME_SIZES_MM) {
            // About 0.6em per character in bold Helvetica, plus room at each end.
            double wide = park.name().length() * size * 0.6 + 5;
            double tall = size + 2;
            Rectangle2D plate = freeSpot(wide, tall, parkOutline, keepClear);
            if (plate != null) {
                out.append("<rect x=\"").append(mm(plate.getX())).append("\" y=\"").append(mm(plate.getY()))
                        .append("\" width=\"").append(mm(wide)).append("\" height=\"").append(mm(tall))
                        .append("\" fill=\"#ffffff\" opacity=\"0.85\"/>")
                        .append("<text x=\"").append(mm(plate.getX() + 2.5))
                        .append("\" y=\"").append(mm(plate.getY() + size + 0.2))
                        .append("\" font-size=\"").append(mm(size))
                        .append("\" font-weight=\"bold\" fill=\"#111\">")
                        .append(escape(park.name())).append("</text>");
                return;
            }
        }
    }

    /**
     * The highest spot this size that stays 1 mm clear of the park outline
     * and touches nothing in keepClear. Null when there is none.
     */
    private static Rectangle2D freeSpot(double wide, double tall, Path2D parkOutline,
                                        List<Rectangle2D> keepClear) {
        double edge = MARGIN_MM - 5;
        for (double y = edge; y <= SHEET_HEIGHT_MM - edge - tall; y += PARK_NAME_STEP_MM) {
            for (double x = edge; x <= SHEET_WIDTH_MM - edge - wide; x += PARK_NAME_STEP_MM) {
                Rectangle2D plate = new Rectangle2D.Double(x, y, wide, tall);
                Rectangle2D withRoom = new Rectangle2D.Double(x - 1, y - 1, wide + 2, tall + 2);
                if (!parkOutline.intersects(withRoom) && !touchesAny(plate, keepClear)) {
                    return plate;
                }
            }
        }
        return null;
    }

    /**
     * The park's outline on the page, in mm: the tightest shape with no dents
     * that holds every corner of every lot (a convex hull).
     */
    private static Path2D parkOutlineOnPage(Park park, Page page) {
        List<double[]> corners = new ArrayList<>();
        for (Lot lot : park.lots()) {
            for (double[] point : lot.ring()) {
                corners.add(new double[] {page.x(point[0], point[1]), page.y(point[0], point[1])});
            }
        }
        // Left to right. The hull is built as a bottom half and a top half,
        // dropping any corner that would make a dent.
        corners.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        List<double[]> hull = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            int start = hull.size();
            for (double[] corner : corners) {
                while (hull.size() >= start + 2
                        && turnsRight(hull.get(hull.size() - 2), hull.get(hull.size() - 1), corner)) {
                    hull.remove(hull.size() - 1);
                }
                hull.add(corner);
            }
            hull.remove(hull.size() - 1);
            corners = corners.reversed();
        }

        Path2D.Double outline = new Path2D.Double();
        for (int i = 0; i < hull.size(); i++) {
            if (i == 0) {
                outline.moveTo(hull.get(i)[0], hull.get(i)[1]);
            } else {
                outline.lineTo(hull.get(i)[0], hull.get(i)[1]);
            }
        }
        outline.closePath();
        return outline;
    }

    /** Whether going a, then b, then c bends clockwise or runs straight. */
    private static boolean turnsRight(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]) <= 0;
    }

    private static boolean touchesAny(Rectangle2D box, List<Rectangle2D> boxes) {
        for (Rectangle2D other : boxes) {
            if (other.intersects(box)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where the north arrow goes: the top left corner of its drawing.
     *
     * <p>The scale bar has the bottom left, so the arrow takes whichever
     * right-hand corner has less park under it.
     */
    private static double[] arrowAt(Park park, Page page, Lot tenant) {
        double bottom = SHEET_HEIGHT_MM - MARGIN_MM;
        double x = SHEET_WIDTH_MM - MARGIN_MM - 7;
        double y = topRightIsBusier(park, page, tenant) ? bottom - 14 : MARGIN_MM + 4;
        if (y > MARGIN_MM + 5 && bottomRightIsBusier(park, page, tenant)) {
            y = MARGIN_MM + 4;
        }
        return new double[] {x, y};
    }

    /** The square around the arrow's white circle. */
    private static Rectangle2D arrowBox(double[] arrow) {
        return new Rectangle2D.Double(arrow[0] + 3 - 10, arrow[1] + 6 - 10, 20, 20);
    }

    /** Scale bar and lot caption bottom left, north arrow in a free corner. */
    private static void drawFurniture(StringBuilder out, Park park, Page page, double turn, double[] arrow,
                                      List<String> credits, boolean onPhoto) {
        double bottom = SHEET_HEIGHT_MM - MARGIN_MM;

        // On a photo the small print along the bottom needs a white strip
        // behind it, or it disappears into the picture.
        if (onPhoto) {
            out.append("<rect x=\"0\" y=\"").append(mm(bottom - 0.5)).append("\" width=\"")
                    .append(mm(SHEET_WIDTH_MM)).append("\" height=\"").append(mm(SHEET_HEIGHT_MM - bottom + 0.5))
                    .append("\" fill=\"#ffffff\" opacity=\"0.85\"/>");
        }

        double metres = scaleBarMetres(page.view().width());
        double barMm = metres * page.view().mmPerMetre();
        double left = MARGIN_MM;

        String caption = "Lot " + park.tenantLotNumber()
                + (park.drawnOn() == null ? "" : "  ·  drawn " + park.drawnOn());

        out.append("<rect x=\"").append(mm(left - 2.5)).append("\" y=\"").append(mm(bottom - 12))
                .append("\" width=\"").append(mm(Math.max(barMm, 45) + 14))
                .append("\" height=\"16\" fill=\"#ffffff\" opacity=\"0.85\"/>")
                .append("<text x=\"").append(mm(left)).append("\" y=\"").append(mm(bottom - 7.5))
                .append("\" font-size=\"3.2\" fill=\"#222\">").append(escape(caption)).append("</text>")
                .append("<g stroke=\"#333\" stroke-width=\"0.4\">")
                .append("<path d=\"M").append(mm(left)).append(' ').append(mm(bottom - 3.5))
                .append(" L").append(mm(left + barMm)).append(' ').append(mm(bottom - 3.5)).append("\"/>")
                .append("<path d=\"M").append(mm(left)).append(' ').append(mm(bottom - 4.7))
                .append(" L").append(mm(left)).append(' ').append(mm(bottom - 2.3)).append("\"/>")
                .append("<path d=\"M").append(mm(left + barMm)).append(' ').append(mm(bottom - 4.7))
                .append(" L").append(mm(left + barMm)).append(' ').append(mm(bottom - 2.3)).append("\"/></g>")
                .append("<text x=\"").append(mm(left + barMm + 2)).append("\" y=\"").append(mm(bottom - 2.6))
                .append("\" font-size=\"2.8\" fill=\"#333\">")
                .append(Math.round(metres * FEET_PER_METRE)).append(" ft</text>");

        // A lease map is not a survey. The note belongs on the drawing, not in
        // the clause, so that the page can be a map and nothing else.
        out.append("<text x=\"").append(mm(SHEET_WIDTH_MM / 2)).append("\" y=\"").append(mm(bottom + 2))
                .append("\" text-anchor=\"middle\" font-size=\"2.4\" fill=\"#6a6a6a\">")
                .append("Measurements are approximate.</text>");

        if (!credits.isEmpty()) {
            // The photo's credit makes the line longer. A line too long for
            // the page is printed smaller rather than cut off.
            String line = String.join("  ·  ", credits);
            double size = Math.min(2.1, (SHEET_WIDTH_MM - 2 * MARGIN_MM) / (line.length() * 0.52));
            out.append("<text x=\"").append(mm(SHEET_WIDTH_MM / 2)).append("\" y=\"").append(mm(bottom + 5))
                    .append("\" text-anchor=\"middle\" font-size=\"").append(mm(size))
                    .append("\" fill=\"#8a8a8a\">").append(escape(line)).append("</text>");
        }

        // North turns with the map, so the arrow is drawn at the same angle.
        double arrowX = arrow[0];
        double arrowY = arrow[1];
        out.append("<circle cx=\"").append(mm(arrowX + 3)).append("\" cy=\"").append(mm(arrowY + 6))
                .append("\" r=\"10\" fill=\"#ffffff\" opacity=\"0.85\"/>")
                .append("<g stroke=\"#333\" fill=\"#333\" transform=\"translate(")
                .append(mm(arrowX)).append(' ').append(mm(arrowY)).append(") rotate(")
                .append(mm(-Math.toDegrees(turn))).append(" 3 6)\">")
                .append("<path d=\"M0 11 L3 0 L6 11 L3 8 Z\" stroke-width=\"0.3\"/>")
                .append("<text x=\"3\" y=\"14.6\" text-anchor=\"middle\" font-size=\"3\" stroke=\"none\">N</text></g>");
    }

    /** Whether the top right corner has more lots in it than the bottom right. */
    private static boolean topRightIsBusier(Park park, Page page, Lot tenant) {
        return lotsInCorner(park, page, tenant, true) > lotsInCorner(park, page, tenant, false);
    }

    /** Whether the bottom right corner holds the tenant's lot or several others. */
    private static boolean bottomRightIsBusier(Park park, Page page, Lot tenant) {
        return lotsInCorner(park, page, tenant, false) > lotsInCorner(park, page, tenant, true);
    }

    /**
     * How much park sits in one right-hand corner.
     *
     * <p>The tenant's own lot counts for a lot: covering any lot is untidy, but
     * covering the one the page exists for is the one to avoid.
     */
    private static int lotsInCorner(Park park, Page page, Lot tenant, boolean top) {
        double left = SHEET_WIDTH_MM - 34;
        double from = top ? 0 : SHEET_HEIGHT_MM - 34;
        double to = top ? 34 : SHEET_HEIGHT_MM;

        int found = 0;
        for (Lot lot : park.lots()) {
            double[][] box = pageBoxOf(lot, page);
            boolean overlaps = box[1][0] > left && box[1][1] > from && box[0][1] < to;
            if (overlaps) {
                found += (lot == tenant) ? 20 : 1;
            }
        }
        return found;
    }

    // ---- geometry ------------------------------------------------------------

    /**
     * Metres east and south of one point on the ground.
     *
     * <p>Good to a few centimetres across a park, which is finer than the plat
     * lines the outlines were traced from.
     */
    record Projection(double lng0, double lat0) {
        double x(double lng, double lat) {
            return (lng - lng0) * 111320 * Math.cos(Math.toRadians(lat0));
        }

        double y(double lng, double lat) {
            return -(lat - lat0) * METRES_PER_DEGREE_LAT;
        }

        /** The other way: meters east and south back to [lng, lat]. */
        double[] lngLat(double x, double y) {
            return new double[] {
                    lng0 + x / (111320 * Math.cos(Math.toRadians(lat0))),
                    lat0 - y / METRES_PER_DEGREE_LAT};
        }
    }

    /** A point in metres, turned by the map's angle. */
    private static double[] turned(Projection to, double turn, double lng, double lat) {
        double x = to.x(lng, lat);
        double y = to.y(lng, lat);
        double cos = Math.cos(turn);
        double sin = Math.sin(turn);
        return new double[] {x * cos - y * sin, x * sin + y * cos};
    }

    /** A rectangle in turned metres: the part of the park a view shows. */
    private record Frame(double minX, double minY, double maxX, double maxY) {
        double width() { return maxX - minX; }
        double height() { return maxY - minY; }

        Frame grownBy(double metres) {
            return new Frame(minX - metres, minY - metres, maxX + metres, maxY + metres);
        }

        /** Stretched to the page's shape, so nothing is squashed. */
        Frame fittedToPage() {
            double pageRatio = SHEET_WIDTH_MM / SHEET_HEIGHT_MM;
            double w = width();
            double h = height();
            if (w / h < pageRatio) {
                double want = h * pageRatio;
                double pad = (want - w) / 2;
                return new Frame(minX - pad, minY, maxX + pad, maxY);
            }
            double want = w / pageRatio;
            double pad = (want - h) / 2;
            return new Frame(minX, minY - pad, maxX, maxY + pad);
        }

        double mmPerMetre() {
            return SHEET_WIDTH_MM / width();
        }
    }

    private static double[] middleOf(List<Lot> lots) {
        double minLng = Double.MAX_VALUE;
        double maxLng = -Double.MAX_VALUE;
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        for (Lot lot : lots) {
            for (double[] point : lot.ring()) {
                minLng = Math.min(minLng, point[0]);
                maxLng = Math.max(maxLng, point[0]);
                minLat = Math.min(minLat, point[1]);
                maxLat = Math.max(maxLat, point[1]);
            }
        }
        return new double[] {(minLng + maxLng) / 2, (minLat + maxLat) / 2};
    }

    private static Frame frameOf(List<Lot> lots, Projection to, double turn) {
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Lot lot : lots) {
            for (double[] point : lot.ring()) {
                double[] at = turned(to, turn, point[0], point[1]);
                minX = Math.min(minX, at[0]);
                maxX = Math.max(maxX, at[0]);
                minY = Math.min(minY, at[1]);
                maxY = Math.max(maxY, at[1]);
            }
        }
        return new Frame(minX, minY, maxX, maxY);
    }

    /** One lot's box on the page, in millimetres: {{left, top}, {right, bottom}}. */
    private static double[][] pageBoxOf(Lot lot, Page page) {
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] point : lot.ring()) {
            double x = page.x(point[0], point[1]);
            double y = page.y(point[0], point[1]);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return new double[][] {{minX, minY}, {maxX, maxY}};
    }

    /** How wide a typical lot would be on paper, in mm. Decides the framing. */
    private static double medianLotWidthMm(List<Lot> lots, Projection to, double turn, Frame frame) {
        return medianLotWidthMetres(lots, to, turn) * frame.mmPerMetre();
    }

    /** The middle lot's narrower side, on the ground. */
    private static double medianLotWidthMetres(List<Lot> lots, Projection to, double turn) {
        List<Double> widths = new ArrayList<>(lots.size());
        for (Lot lot : lots) {
            Frame one = frameOf(List.of(lot), to, turn);
            widths.add(Math.min(one.width(), one.height()));
        }
        widths.sort(Double::compareTo);
        return widths.isEmpty() ? 0 : widths.get(widths.size() / 2);
    }

    /**
     * As much of the park as fits with its lots still readable, centred on the
     * tenant's lot. What does not fit runs off the edge.
     */
    private static Frame readableViewAround(List<Lot> lots, Lot tenant, Projection to, double turn) {
        Frame one = frameOf(List.of(tenant), to, turn);
        double centreX = (one.minX() + one.maxX()) / 2;
        double centreY = (one.minY() + one.maxY()) / 2;

        double typical = medianLotWidthMetres(lots, to, turn);
        double mmPerMetre = READABLE_LOT_WIDTH_MM / Math.max(typical, 0.5);
        double halfWidth = SHEET_WIDTH_MM / mmPerMetre / 2;
        double halfHeight = SHEET_HEIGHT_MM / mmPerMetre / 2;

        return new Frame(centreX - halfWidth, centreY - halfHeight,
                centreX + halfWidth, centreY + halfHeight);
    }

    /**
     * Where a lot's number goes: the middle of its widest horizontal slice.
     *
     * <p>Not the average of the corners, which lands outside an L-shaped lot.
     */
    static double[] labelPoint(double[][] ring) {
        double midLat = 0;
        for (double[] point : ring) {
            midLat += point[1];
        }
        midLat /= ring.length;

        List<Double> crossings = new ArrayList<>();
        for (int i = 0; i < ring.length; i++) {
            double[] a = ring[i];
            double[] b = ring[(i + 1) % ring.length];
            if ((a[1] <= midLat && b[1] > midLat) || (b[1] <= midLat && a[1] > midLat)) {
                double t = (midLat - a[1]) / (b[1] - a[1]);
                crossings.add(a[0] + t * (b[0] - a[0]));
            }
        }
        crossings.sort(Double::compareTo);

        double bestFrom = 0;
        double bestTo = 0;
        for (int i = 0; i + 1 < crossings.size(); i += 2) {
            if (crossings.get(i + 1) - crossings.get(i) > bestTo - bestFrom) {
                bestFrom = crossings.get(i);
                bestTo = crossings.get(i + 1);
            }
        }
        if (bestTo == bestFrom) {
            double midLng = 0;
            for (double[] point : ring) {
                midLng += point[0];
            }
            return new double[] {midLng / ring.length, midLat};
        }
        return new double[] {(bestFrom + bestTo) / 2, midLat};
    }

    /** A round number of feet that fills about a fifth of the page. */
    private static double scaleBarMetres(double frameWidthMetres) {
        double wantFeet = frameWidthMetres * FEET_PER_METRE / 5;
        double[] steps = {10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000};
        double feet = steps[0];
        for (double step : steps) {
            if (step <= wantFeet) {
                feet = step;
            }
        }
        return feet / FEET_PER_METRE;
    }


    // ---- output --------------------------------------------------------------

    /** Millimetres, rounded to a hundredth. Enough for print, short in the file. */
    private static String mm(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        if (text.endsWith(".00")) {
            return text.substring(0, text.length() - 3);
        }
        if (text.endsWith("0")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

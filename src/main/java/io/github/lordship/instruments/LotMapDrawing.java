package io.github.lordship.instruments;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 * <p>Pure. Coordinates in, SVG out. No database, no Spring, no file system, so
 * the drawing can be unit tested the way a number format is.
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
     * The page, as one SVG element.
     *
     * @throws IllegalArgumentException when the tenant's lot is not in the park
     */
    public static String draw(Park park) {
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
        StringBuilder out = new StringBuilder(16384);

        // One font for the whole drawing. Without it each renderer picks its
        // own default and the same lease prints differently on two machines.
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"lot-map\" ")
                .append("font-family=\"Helvetica, Arial, sans-serif\" ")
                .append("width=\"").append(mm(SHEET_WIDTH_MM)).append("mm\" ")
                .append("height=\"").append(mm(SHEET_HEIGHT_MM)).append("mm\" ")
                .append("viewBox=\"0 0 ").append(mm(SHEET_WIDTH_MM)).append(' ')
                .append(mm(SHEET_HEIGHT_MM)).append("\">")
                .append("<rect x=\"0\" y=\"0\" width=\"").append(mm(SHEET_WIDTH_MM))
                .append("\" height=\"").append(mm(SHEET_HEIGHT_MM)).append("\" fill=\"#ffffff\"/>");

        if (cropped) {
            out.append(fadeDefs());
        }

        drawWater(out, park, page);
        drawRoads(out, park, page);
        drawBoundary(out, park, page);
        drawLots(out, park, tenant, page);
        drawHome(out, park, page);
        drawLotNumbers(out, park, tenant, page);

        if (cropped) {
            drawFade(out);
        }
        drawParkName(out, park);
        drawFurniture(out, park, page, turn, tenant);

        out.append("</svg>");
        return out.toString();
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
        drawRoadNames(out, park, page);
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
    private static void drawRoadNames(StringBuilder out, Park park, Page page) {
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

            out.append("<text x=\"").append(mm(midX)).append("\" y=\"").append(mm(midY))
                    .append("\" transform=\"rotate(").append(mm(angle)).append(' ')
                    .append(mm(midX)).append(' ').append(mm(midY)).append(")\"")
                    .append(" text-anchor=\"middle\" dy=\"-0.9\" font-size=\"").append(mm(ROAD_LABEL_MM))
                    .append("\" fill=\"#6b6b6b\">")
                    .append(escape(road.name())).append("</text>");
        }
    }

    private static void drawBoundary(StringBuilder out, Park park, Page page) {
        if (park.boundary() == null) {
            return;
        }
        out.append("<path d=\"").append(page.path(park.boundary().ring(), true))
                .append("\" fill=\"none\" stroke=\"#8a8a8a\" stroke-width=\"0.5\" stroke-dasharray=\"2.5 1.8\"/>");
    }

    private static void drawLots(StringBuilder out, Park park, Lot tenant, Page page) {
        for (Lot lot : park.lots()) {
            boolean isTenant = lot == tenant;
            out.append("<path d=\"").append(page.path(lot.ring(), true))
                    .append("\" fill=\"").append(isTenant ? "#f0dca8" : "#fbfbf9")
                    .append("\" stroke=\"#333\" stroke-width=\"")
                    .append(isTenant ? "0.8" : "0.25").append("\"/>");
        }
    }

    private static void drawHome(StringBuilder out, Park park, Page page) {
        if (park.home() == null) {
            return;
        }
        out.append("<path d=\"").append(page.path(park.home().ring(), true))
                .append("\" fill=\"#d8cfae\" stroke=\"#5a5244\" stroke-width=\"0.3\"/>");
    }

    /**
     * The lot numbers, each sized to the lot it belongs to.
     *
     * <p>A number wider than its lot is worse than no number: at park scale the
     * dense rows turn into a smudge of overlapping text. A lot that cannot hold
     * its number keeps its outline and loses the label. The tenant's own lot
     * always keeps it, since that is the one the page is about.
     */
    private static void drawLotNumbers(StringBuilder out, Park park, Lot tenant, Page page) {
        for (Lot lot : park.lots()) {
            boolean isTenant = lot == tenant;
            double size = labelSizeMm(lot, page, isTenant);
            if (size <= 0) {
                continue;
            }
            double[] at = labelPoint(lot.ring());
            out.append("<text x=\"").append(mm(page.x(at[0], at[1])))
                    .append("\" y=\"").append(mm(page.y(at[0], at[1]) + size * 0.35))
                    .append("\" text-anchor=\"middle\" font-size=\"").append(mm(size))
                    .append("\" font-weight=\"").append(isTenant ? "bold" : "normal")
                    .append("\" fill=\"#222\">").append(escape(lot.number())).append("</text>");
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

    /** The community's name, top left, on a plate so it reads over the map. */
    private static void drawParkName(StringBuilder out, Park park) {
        double size = 6;
        double width = park.name().length() * size * 0.55 + 5;
        out.append("<rect x=\"").append(mm(MARGIN_MM - 2.5)).append("\" y=\"").append(mm(MARGIN_MM - 5))
                .append("\" width=\"").append(mm(width))
                .append("\" height=\"8\" fill=\"#ffffff\" opacity=\"0.85\"/>")
                .append("<text x=\"").append(mm(MARGIN_MM)).append("\" y=\"").append(mm(MARGIN_MM))
                .append("\" font-size=\"").append(mm(size))
                .append("\" font-weight=\"bold\" fill=\"#111\">")
                .append(escape(park.name())).append("</text>");
    }

    /** Scale bar and lot caption bottom left, north arrow in a free corner. */
    private static void drawFurniture(StringBuilder out, Park park, Page page, double turn, Lot tenant) {
        double bottom = SHEET_HEIGHT_MM - MARGIN_MM;

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

        if (!park.credits().isEmpty()) {
            out.append("<text x=\"").append(mm(SHEET_WIDTH_MM / 2)).append("\" y=\"").append(mm(bottom + 5))
                    .append("\" text-anchor=\"middle\" font-size=\"2.1\" fill=\"#8a8a8a\">")
                    .append(escape(String.join("  ·  ", park.credits()))).append("</text>");
        }

        // North turns with the map, so the arrow is drawn at the same angle.
        // The name has the top left and the scale bar the bottom left, so the
        // arrow takes whichever right-hand corner has less park under it.
        double arrowX = SHEET_WIDTH_MM - MARGIN_MM - 7;
        double arrowY = topRightIsBusier(park, page, tenant) ? bottom - 14 : MARGIN_MM + 4;
        if (arrowY > MARGIN_MM + 5 && bottomRightIsBusier(park, page, tenant)) {
            arrowY = MARGIN_MM + 4;
        }
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

package io.github.lordship.sitemaps.internal;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The ground's height around a park: a shaded picture of the ground (hillshade)
 * and contour lines, worked out from a grid of heights.
 *
 * <p>The heights come from USGS 3DEP, "best available": 1 m lidar where it exists,
 * coarser elsewhere. Public domain, no key. Meters above the NAVD88 datum.
 *
 * <p>The grid is asked for in Web Mercator with square pixels, so a pixel (col, row)
 * turns into a place with simple arithmetic. Ported from the office tool's elevation.py.
 */
final class Elevation {

    private Elevation() { }

    static final double FT_PER_M = 3.28084;
    static final double EARTH_RADIUS = 6378137;

    // Contour spacing is picked from these, in feet: the smallest step that
    // gives no more than about 25 lines across the park's height range
    private static final int[] CONTOUR_STEPS_FT = {1, 2, 5, 10, 20, 25, 50, 100};
    private static final int MAX_CONTOUR_LINES = 25;
    static final int INDEX_EVERY = 5;           // every fifth contour is drawn heavier and labeled
    private static final double MIN_CONTOUR_M = 15; // contour scraps shorter than this are dropped
    private static final int MAX_GRID_PX = 2000;
    private static final double BLUR_SIGMA = 1.5;

    /** An area in degrees. */
    record Box(double south, double west, double north, double east) { }

    /**
     * What to ask USGS for: a Web Mercator box (xmin, ymin, xmax, ymax) and a size in pixels.
     * About one pixel per meter on the ground, capped so a big area does not become a huge file.
     */
    record GridRequest(double[] bbox3857, int width, int height) { }

    /** What the map pages need to know about a park's heights. */
    record Summary(
            double[] bbox3857,
            int width,
            int height,
            double metersPerPx,
            double minFt,
            double maxFt,
            int contourIntervalFt
    ) { }

    /** One contour line, [longitude, latitude] points. index: drawn heavier and labeled. */
    record Contour(double levelFt, boolean index, List<double[]> points) { }

    /** Everything worked out from one grid. Nothing is saved here. */
    record Built(Summary summary, List<Contour> contours, byte[] hillshadePng) { }

    // ---- Web Mercator -------------------------------------------------------------

    static double[] toMercator(double lng, double lat) {
        return new double[] {
                EARTH_RADIUS * Math.toRadians(lng),
                EARTH_RADIUS * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2))
        };
    }

    static double[] fromMercator(double x, double y) {
        return new double[] {
                Math.toDegrees(x / EARTH_RADIUS),
                Math.toDegrees(2 * Math.atan(Math.exp(y / EARTH_RADIUS)) - Math.PI / 2)
        };
    }

    static GridRequest gridFor(Box box) {
        double[] sw = toMercator(box.west(), box.south());
        double[] ne = toMercator(box.east(), box.north());
        double k = Math.cos(Math.toRadians((box.south() + box.north()) / 2)); // ground meters per Mercator meter
        double widthM = (ne[0] - sw[0]) * k;
        double heightM = (ne[1] - sw[1]) * k;
        double metersPerPx = Math.max(1.0, Math.max(widthM, heightM) / MAX_GRID_PX);
        int width = Math.max(2, (int) Math.round(widthM / metersPerPx));
        int height = Math.max(2, (int) Math.round(heightM / metersPerPx));
        return new GridRequest(new double[] {sw[0], sw[1], ne[0], ne[1]}, width, height);
    }

    // ---- putting it together --------------------------------------------------------

    /**
     * Works out the summary, contour lines and hillshade from a grid of heights in meters.
     * Throws IllegalArgumentException when the grid holds no heights at all.
     */
    static Built build(Box box, GridRequest request, HeightTiff.Grid raw) {
        float[] grid = clean(raw.values());
        int width = raw.width();
        int height = raw.height();
        double[] bbox = request.bbox3857();

        float low = Float.POSITIVE_INFINITY;
        float high = Float.NEGATIVE_INFINITY;
        for (float v : grid) {
            if (!Float.isNaN(v)) {
                low = Math.min(low, v);
                high = Math.max(high, v);
            }
        }
        if (low > high) {
            throw new IllegalArgumentException("USGS has no heights for this area.");
        }

        double k = Math.cos(Math.toRadians((box.south() + box.north()) / 2));
        double metersPerPx = (bbox[2] - bbox[0]) / width * k;
        int interval = contourIntervalFt(grid);

        List<Contour> contours = new ArrayList<>();
        for (Line line : contourLines(grid, width, height, interval)) {
            double lengthPx = 0;
            for (int i = 1; i < line.points.size(); i++) {
                double[] a = line.points.get(i - 1);
                double[] b = line.points.get(i);
                lengthPx += Math.hypot(b[0] - a[0], b[1] - a[1]);
            }
            if (lengthPx * metersPerPx < MIN_CONTOUR_M) {
                continue;
            }
            List<double[]> lngLat = new ArrayList<>(line.points.size());
            for (double[] p : line.points) {
                lngLat.add(pixelToLngLat(bbox, width, height, p[0], p[1]));
            }
            boolean index = Math.floorMod(Math.round(line.levelFt / interval), INDEX_EVERY) == 0;
            contours.add(new Contour(line.levelFt, index, lngLat));
        }

        Summary summary = new Summary(bbox, width, height,
                Math.round(metersPerPx * 1000) / 1000.0,
                Math.round(low * FT_PER_M * 10) / 10.0,
                Math.round(high * FT_PER_M * 10) / 10.0,
                interval);
        return new Built(summary, contours, hillshadePng(grid, width, height, metersPerPx));
    }

    // The grid with the service's "no data" values turned into NaN
    static float[] clean(float[] values) {
        float[] out = values.clone();
        for (int i = 0; i < out.length; i++) {
            if (out[i] < -500 || out[i] > 9000) {
                out[i] = Float.NaN;
            }
        }
        return out;
    }

    /**
     * Pixel (x, y) to [longitude, latitude]. x and y are corner-based, as marching squares
     * gives them: (0, 0) is the middle of the top-left pixel.
     */
    static double[] pixelToLngLat(double[] bbox, int width, int height, double x, double y) {
        double px = (bbox[2] - bbox[0]) / width;
        double py = (bbox[3] - bbox[1]) / height;
        return fromMercator(bbox[0] + (x + 0.5) * px, bbox[3] - (y + 0.5) * py);
    }

    // ---- hillshade ---------------------------------------------------------------------

    /**
     * Shaded relief as a PNG: grey shading, see-through where there is no height.
     * Light comes from the north-west, the map convention.
     */
    static byte[] hillshadePng(float[] grid, int width, int height, double metersPerPx) {
        float[] filled = filled(grid);
        double light = Math.toRadians(90.0 - 315.0);  // compass bearing to a math angle
        double altitude = Math.toRadians(45.0);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                // Slopes the way numpy.gradient works them out: middle differences inside, one-sided at the edges
                double dRow = slope(filled, width, height, c, r, false) / metersPerPx;
                double dCol = slope(filled, width, height, c, r, true) / metersPerPx;
                double riseEast = dCol;
                double riseNorth = -dRow;                 // rows run south as they go down
                double slope = Math.atan(Math.hypot(riseEast, riseNorth));
                double downhill = Math.atan2(-riseNorth, -riseEast); // the way the ground faces
                double shade = Math.sin(altitude) * Math.cos(slope)
                        + Math.cos(altitude) * Math.sin(slope) * Math.cos(light - downhill);
                int grey = (int) Math.max(0, Math.min(255, shade * 255));
                int alpha = Float.isNaN(grid[r * width + c]) ? 0 : 255;
                image.setRGB(c, r, (alpha << 24) | (grey << 16) | (grey << 8) | grey);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // The change in height per pixel, along a row (east) or down a column (south)
    private static double slope(float[] g, int width, int height, int c, int r, boolean alongRow) {
        int n = alongRow ? width : height;
        int i = alongRow ? c : r;
        if (n < 2) {
            return 0;
        }
        int before = Math.max(0, i - 1);
        int after = Math.min(n - 1, i + 1);
        float a = alongRow ? g[r * width + before] : g[before * width + c];
        float b = alongRow ? g[r * width + after] : g[after * width + c];
        return (b - a) / (double) (after - before);
    }

    // Missing heights filled with the average height, so slopes can be worked out next to them
    private static float[] filled(float[] grid) {
        double sum = 0;
        int count = 0;
        for (float v : grid) {
            if (!Float.isNaN(v)) {
                sum += v;
                count++;
            }
        }
        float mean = count == 0 ? 0 : (float) (sum / count);
        float[] out = grid.clone();
        for (int i = 0; i < out.length; i++) {
            if (Float.isNaN(out[i])) {
                out[i] = mean;
            }
        }
        return out;
    }

    // ---- contours ------------------------------------------------------------------------

    /** The contour step, in feet, that suits this park's height range. */
    static int contourIntervalFt(float[] grid) {
        float[] values = valid(grid);
        if (values.length == 0) {
            return CONTOUR_STEPS_FT[CONTOUR_STEPS_FT.length - 1];
        }
        Arrays.sort(values);
        double low = percentile(values, 2) * FT_PER_M;
        double high = percentile(values, 98) * FT_PER_M;
        double relief = Math.max(high - low, 0.1);
        for (int step : CONTOUR_STEPS_FT) {
            if (relief / step <= MAX_CONTOUR_LINES) {
                return step;
            }
        }
        return CONTOUR_STEPS_FT[CONTOUR_STEPS_FT.length - 1];
    }

    // Like numpy.percentile: a straight line between the two nearest sorted values
    private static double percentile(float[] sorted, double p) {
        double at = p / 100 * (sorted.length - 1);
        int below = (int) Math.floor(at);
        int above = Math.min(sorted.length - 1, below + 1);
        return sorted[below] + (sorted[above] - sorted[below]) * (at - below);
    }

    private static float[] valid(float[] grid) {
        int n = 0;
        for (float v : grid) {
            if (!Float.isNaN(v)) {
                n++;
            }
        }
        float[] out = new float[n];
        int i = 0;
        for (float v : grid) {
            if (!Float.isNaN(v)) {
                out[i++] = v;
            }
        }
        return out;
    }

    /** A contour line in pixel units, before it is turned into longitude and latitude. */
    record Line(double levelFt, List<double[]> points) { }

    /**
     * Every contour line, in pixel units. The heights are blurred a little first:
     * 1 m lidar picks up curbs and ditches, which make contours jagged without making the map truer.
     */
    static List<Line> contourLines(float[] grid, int width, int height, int intervalFt) {
        float[] smooth = blur(filled(grid), width, height, BLUR_SIGMA);
        float low = Float.POSITIVE_INFINITY;
        float high = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < smooth.length; i++) {
            if (Float.isNaN(grid[i])) {
                smooth[i] = Float.NaN;
            } else {
                low = Math.min(low, smooth[i]);
                high = Math.max(high, smooth[i]);
            }
        }
        List<Line> out = new ArrayList<>();
        if (low > high) {
            return out;
        }
        double lowFt = Math.ceil(low * FT_PER_M / intervalFt) * intervalFt;
        double highFt = Math.floor(high * FT_PER_M / intervalFt) * intervalFt;
        for (double levelFt = lowFt; levelFt <= highFt; levelFt += intervalFt) {
            for (List<double[]> line : join(segments(smooth, width, height, levelFt / FT_PER_M))) {
                if (line.size() >= 3) {
                    out.add(new Line(levelFt, line));
                }
            }
        }
        return out;
    }

    // A Gaussian blur, the same as OpenCV's GaussianBlur with this sigma:
    // a 13-wide kernel for 1.5, edges mirrored without repeating the edge pixel
    static float[] blur(float[] g, int width, int height, double sigma) {
        int size = ((int) Math.round(sigma * 8 + 1)) | 1;
        int half = size / 2;
        double[] kernel = new double[size];
        double total = 0;
        for (int i = 0; i < size; i++) {
            double d = i - half;
            kernel[i] = Math.exp(-(d * d) / (2 * sigma * sigma));
            total += kernel[i];
        }
        for (int i = 0; i < size; i++) {
            kernel[i] /= total;
        }
        float[] across = new float[g.length];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                double sum = 0;
                for (int k = 0; k < size; k++) {
                    sum += kernel[k] * g[r * width + mirror(c + k - half, width)];
                }
                across[r * width + c] = (float) sum;
            }
        }
        float[] out = new float[g.length];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                double sum = 0;
                for (int k = 0; k < size; k++) {
                    sum += kernel[k] * across[mirror(r + k - half, height) * width + c];
                }
                out[r * width + c] = (float) sum;
            }
        }
        return out;
    }

    // -1 becomes 1, n becomes n - 2
    private static int mirror(int i, int n) {
        if (n == 1) {
            return 0;
        }
        while (i < 0 || i >= n) {
            i = i < 0 ? -i : 2 * n - 2 - i;
        }
        return i;
    }

    // Marching squares. The corners of each cell are weighted top-left 8, top-right 4,
    // bottom-right 2, bottom-left 1 when above the level. Each case lists the cell edges
    // the line crosses: t = top, r = right, b = bottom, l = left.
    private static final char[][][] CASES = {
            {},
            {{'l', 'b'}}, {{'b', 'r'}}, {{'l', 'r'}}, {{'t', 'r'}},
            {{'l', 't'}, {'b', 'r'}}, {{'t', 'b'}}, {{'l', 't'}},
            {{'l', 't'}}, {{'t', 'b'}}, {{'t', 'r'}, {'l', 'b'}},
            {{'t', 'r'}}, {{'l', 'r'}}, {{'b', 'r'}}, {{'l', 'b'}},
            {},
    };

    // Loose two-point pieces of one contour level: [ax, ay, bx, by] in pixel units
    private static List<double[]> segments(float[] g, int width, int height, double level) {
        List<double[]> out = new ArrayList<>();
        for (int r = 0; r + 1 < height; r++) {
            for (int c = 0; c + 1 < width; c++) {
                float tl = g[r * width + c];
                float tr = g[r * width + c + 1];
                float br = g[(r + 1) * width + c + 1];
                float bl = g[(r + 1) * width + c];
                if (Float.isNaN(tl) || Float.isNaN(tr) || Float.isNaN(br) || Float.isNaN(bl)) {
                    continue;
                }
                int kind = (tl > level ? 8 : 0) + (tr > level ? 4 : 0) + (br > level ? 2 : 0) + (bl > level ? 1 : 0);
                for (char[] pair : CASES[kind]) {
                    double[] a = edgePoint(pair[0], r, c, tl, tr, br, bl, level);
                    double[] b = edgePoint(pair[1], r, c, tl, tr, br, bl, level);
                    out.add(new double[] {a[0], a[1], b[0], b[1]});
                }
            }
        }
        return out;
    }

    // Where the level crosses one edge of a cell. Neighboring cells work out the same numbers
    // for the edge they share, so the pieces join up exactly.
    private static double[] edgePoint(char edge, int r, int c, double tl, double tr, double br, double bl, double level) {
        return switch (edge) {
            case 't' -> new double[] {c + (level - tl) / (tr - tl), r};
            case 'b' -> new double[] {c + (level - bl) / (br - bl), r + 1.0};
            case 'l' -> new double[] {c, r + (level - tl) / (bl - tl)};
            default -> new double[] {c + 1.0, r + (level - tr) / (br - tr)};
        };
    }

    // Chains loose two-point pieces into continuous lines
    private static List<List<double[]>> join(List<double[]> segments) {
        Map<Long, List<int[]>> ends = new HashMap<>();
        for (int n = 0; n < segments.size(); n++) {
            double[] s = segments.get(n);
            ends.computeIfAbsent(key(s[0], s[1]), k -> new ArrayList<>()).add(new int[] {n, 0});
            ends.computeIfAbsent(key(s[2], s[3]), k -> new ArrayList<>()).add(new int[] {n, 1});
        }
        boolean[] used = new boolean[segments.size()];
        List<List<double[]>> lines = new ArrayList<>();
        for (int start = 0; start < segments.size(); start++) {
            if (used[start]) {
                continue;
            }
            used[start] = true;
            double[] s = segments.get(start);
            java.util.ArrayDeque<double[]> line = new java.util.ArrayDeque<>();
            line.add(new double[] {s[0], s[1]});
            line.add(new double[] {s[2], s[3]});
            // Grow forwards from the end, then backwards from the start
            for (boolean forwards : new boolean[] {true, false}) {
                while (true) {
                    double[] tip = forwards ? line.getLast() : line.getFirst();
                    int[] found = null;
                    for (int[] e : ends.getOrDefault(key(tip[0], tip[1]), List.of())) {
                        if (!used[e[0]]) {
                            found = e;
                            break;
                        }
                    }
                    if (found == null) {
                        break;
                    }
                    used[found[0]] = true;
                    double[] next = segments.get(found[0]);
                    double[] point = found[1] == 0 ? new double[] {next[2], next[3]} : new double[] {next[0], next[1]};
                    if (forwards) {
                        line.addLast(point);
                    } else {
                        line.addFirst(point);
                    }
                }
            }
            lines.add(new ArrayList<>(line));
        }
        return lines;
    }

    // A point rounded to 5 decimals, as one number for a map key
    private static long key(double x, double y) {
        return Math.round(x * 1e5) * 4_000_000_000L + Math.round(y * 1e5);
    }
}

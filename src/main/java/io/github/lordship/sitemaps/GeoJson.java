package io.github.lordship.sitemaps;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the coordinates out of the GeoJSON that PostGIS hands back.
 *
 * <p>Only what a map page needs: the lists of points. Types, holes, properties
 * and everything else in the format are ignored, because the drawing only ever
 * asks "which points, in which order".
 *
 * <p>Points are [longitude, latitude], the order GeoJSON uses. Latitude first
 * is the usual way to put a park in the ocean.
 */
public final class GeoJson {

    private GeoJson() {}

    /**
     * Every ring or line in a geometry, outermost first.
     *
     * <p>A Polygon gives its outer ring then its holes; a MultiPolygon gives
     * every part; a LineString gives one list. An empty or unreadable geometry
     * gives an empty list rather than throwing, because a missing pond is not a
     * reason to refuse a lease.
     */
    public static List<double[][]> parts(String geoJson) {
        List<double[][]> parts = new ArrayList<>();
        if (geoJson == null) {
            return parts;
        }

        int at = geoJson.indexOf("\"coordinates\"");
        if (at < 0) {
            return parts;
        }

        List<double[]> points = new ArrayList<>();
        int depth = 0;
        int innermost = 0;
        StringBuilder number = new StringBuilder();
        List<Double> pair = new ArrayList<>(2);

        for (int i = geoJson.indexOf('[', at); i >= 0 && i < geoJson.length(); i++) {
            char here = geoJson.charAt(i);
            if (here == '[') {
                depth++;
                innermost = Math.max(innermost, depth);
                continue;
            }
            if (here == ',' || here == ']') {
                if (!number.isEmpty()) {
                    pair.add(Double.parseDouble(number.toString()));
                    number.setLength(0);
                }
                if (here == ']') {
                    if (pair.size() >= 2) {
                        points.add(new double[] {pair.get(0), pair.get(1)});
                    }
                    pair.clear();
                    depth--;
                    // Closing a list of points ends a ring or a line.
                    if (depth == innermost - 2 && !points.isEmpty()) {
                        parts.add(points.toArray(new double[0][]));
                        points = new ArrayList<>();
                    }
                    if (depth == 0) {
                        break;
                    }
                }
                continue;
            }
            if (here == '-' || here == '.' || here == 'e' || here == 'E' || here == '+'
                    || (here >= '0' && here <= '9')) {
                number.append(here);
            }
        }

        // A LineString has no enclosing list, so its points are still in hand.
        if (!points.isEmpty()) {
            parts.add(points.toArray(new double[0][]));
        }
        return parts;
    }

    /** The first ring or line, or null when there is none. */
    public static double[][] first(String geoJson) {
        List<double[][]> parts = parts(geoJson);
        return parts.isEmpty() ? null : parts.get(0);
    }
}

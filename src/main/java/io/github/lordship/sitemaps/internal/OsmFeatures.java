package io.github.lordship.sitemaps.internal;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns what OpenStreetMap sends back (Overpass "out tags geom") into map_feature rows.
 * Ported from the office tool's surroundings.py.
 */
final class OsmFeatures {

    private OsmFeatures() { }

    /**
     * One row for map_feature.
     *
     * @param lines     the shape as lines of [longitude, latitude] points
     * @param buildArea true when the lines outline an area (a pond, a building) that the
     *                  database should turn into a filled shape
     * @param ref       OpenStreetMap's own id, such as osm:way/123
     * @param details   how to draw it, plus OpenStreetMap's own tags under "tags"
     */
    record Row(String kind, String name, List<List<double[]>> lines, boolean buildArea, String ref,
               Map<String, Object> details) { }

    /** The rows, and every shoreline on its own, for working out which side is the sea. */
    record Result(List<Row> rows, List<List<double[]>> coastlines) { }

    // OpenStreetMap road types grouped into four drawing classes, with the
    // width to draw each class when the road itself gives none
    record RoadClass(String name, Set<String> types, double widthMeters) { }

    private static final List<RoadClass> ROAD_CLASSES = List.of(
            new RoadClass("major", Set.of("motorway", "motorway_link", "trunk", "trunk_link", "primary",
                    "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link"), 12.0),
            new RoadClass("local", Set.of("residential", "unclassified", "living_street", "road"), 8.0),
            new RoadClass("service", Set.of("service"), 5.0),
            new RoadClass("path", Set.of("footway", "path", "cycleway", "track", "steps", "pedestrian",
                    "bridleway"), 2.0));

    static Result rows(JsonNode elements) {
        List<Row> rows = new ArrayList<>();
        List<List<double[]>> coastlines = new ArrayList<>();
        if (elements == null || !elements.isArray()) {
            return new Result(rows, coastlines);
        }
        for (JsonNode el : elements) {
            JsonNode tags = el.path("tags");
            String type = el.path("type").asString("");
            String ref = "osm:" + type + "/" + el.path("id").asString("");
            String name = tags.hasNonNull("name") ? tags.get("name").asString() : null;

            if (type.equals("relation")) {
                // A lake or pond drawn as several pieces. The database joins its lines into an area.
                List<List<double[]>> lines = new ArrayList<>();
                for (JsonNode member : el.path("members")) {
                    if (member.path("type").asString("").equals("way")) {
                        List<double[]> line = points(member.path("geometry"));
                        if (line.size() >= 2) {
                            lines.add(line);
                        }
                    }
                }
                if (!lines.isEmpty()) {
                    rows.add(new Row("water", name, lines, true, ref,
                            details("water", tag(tags, "water", "lake"), "tags", tags)));
                }
                continue;
            }

            List<double[]> line = points(el.path("geometry"));
            if (line.size() < 2) {
                continue;
            }

            if (tags.has("highway")) {
                String highway = tags.get("highway").asString();
                RoadClass roadClass = roadClass(highway);
                rows.add(new Row("road", name, List.of(line), false, ref, details(
                        "class", roadClass.name(),
                        "width_m", width(tags, roadClass.widthMeters()),
                        "highway", highway,
                        "oneway", "yes".equals(tag(tags, "oneway", "")),
                        "tags", tags)));
            } else if ("coastline".equals(tag(tags, "natural", ""))) {
                coastlines.add(line);
                rows.add(new Row("coastline", name, List.of(line), false, ref, details("tags", tags)));
            } else if ("water".equals(tag(tags, "natural", "")) && isClosed(line)) {
                rows.add(new Row("water", name, List.of(line), true, ref,
                        details("water", tag(tags, "water", "pond"), "tags", tags)));
            } else if (tags.has("waterway")) {
                String waterway = tags.get("waterway").asString();
                double fallback = waterway.equals("river") || waterway.equals("canal") ? 3.0 : 1.0;
                rows.add(new Row("stream", name, List.of(line), false, ref, details(
                        "waterway", waterway,
                        "width_m", width(tags, fallback),
                        "tags", tags)));
            } else if (tags.has("building") && isClosed(line)) {
                rows.add(new Row("building", name, List.of(line), true, ref, details(
                        "building", tags.get("building").asString(),
                        "tags", tags)));
            }
        }
        return new Result(rows, coastlines);
    }

    /**
     * Points just on the water side of each shoreline. OpenStreetMap draws every shoreline
     * with the land on the LEFT, so a point a meter to the right of it is in the water.
     * The pulled area is cut along the shoreline, and the pieces holding these points are water.
     */
    static List<double[]> seaTestPoints(List<List<double[]>> coastlines, double cosLat) {
        List<double[]> out = new ArrayList<>();
        double oneMeterLat = 1 / 111320.0;
        double oneMeterLng = oneMeterLat / cosLat;
        for (List<double[]> line : coastlines) {
            for (int i = 1; i < line.size(); i++) {
                double[] a = line.get(i - 1);
                double[] b = line.get(i);
                double dx = (b[0] - a[0]) * cosLat;
                double dy = b[1] - a[1];
                double length = Math.hypot(dx, dy);
                if (length == 0) {
                    continue;
                }
                // Right of the direction of travel is (dy, -dx)
                double midLng = (a[0] + b[0]) / 2;
                double midLat = (a[1] + b[1]) / 2;
                out.add(new double[] {midLng + dy / length * oneMeterLng, midLat - dx / length * oneMeterLat});
            }
        }
        return out;
    }

    static RoadClass roadClass(String highway) {
        for (RoadClass c : ROAD_CLASSES) {
            if (c.types().contains(highway)) {
                return c;
            }
        }
        return ROAD_CLASSES.get(1);
    }

    // The road's width in meters if OpenStreetMap has one, else the fallback
    static double width(JsonNode tags, double fallback) {
        String text = tag(tags, "width", "").replace("m", "").strip();
        try {
            double w = Double.parseDouble(text);
            return Double.isFinite(w) ? w : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<double[]> points(JsonNode geometry) {
        List<double[]> out = new ArrayList<>();
        for (JsonNode p : geometry) {
            if (p.has("lon") && p.has("lat")) {
                out.add(new double[] {p.get("lon").asDouble(), p.get("lat").asDouble()});
            }
        }
        return out;
    }

    private static boolean isClosed(List<double[]> line) {
        double[] first = line.getFirst();
        double[] last = line.getLast();
        return line.size() >= 4 && first[0] == last[0] && first[1] == last[1];
    }

    private static String tag(JsonNode tags, String key, String fallback) {
        JsonNode v = tags.get(key);
        return v == null || v.isNull() ? fallback : v.asString();
    }

    private static Map<String, Object> details(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }
}

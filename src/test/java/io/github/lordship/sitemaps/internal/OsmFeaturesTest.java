package io.github.lordship.sitemaps.internal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Turning OpenStreetMap's answer into map rows
class OsmFeaturesTest {

    private final ObjectMapper json = JsonMapper.builder().build();

    @Test
    void roadsBuildingsAndPonds_becomeRows() {
        String elements = """
                [ { "type": "way", "id": 1, "tags": { "highway": "service", "name": "Lot Loop", "width": "6.5 m" },
                    "geometry": [ { "lat": 47.1, "lon": -122.5 }, { "lat": 47.1, "lon": -122.4 } ] },
                  { "type": "way", "id": 2, "tags": { "highway": "footway" },
                    "geometry": [ { "lat": 47.1, "lon": -122.5 }, { "lat": 47.2, "lon": -122.5 } ] },
                  { "type": "way", "id": 3, "tags": { "building": "house" },
                    "geometry": [ { "lat": 0, "lon": 0 }, { "lat": 0, "lon": 1 }, { "lat": 1, "lon": 1 }, { "lat": 0, "lon": 0 } ] },
                  { "type": "way", "id": 4, "tags": { "building": "yes" },
                    "geometry": [ { "lat": 0, "lon": 0 }, { "lat": 0, "lon": 1 }, { "lat": 1, "lon": 1 } ] },
                  { "type": "relation", "id": 5, "tags": { "natural": "water", "name": "Lake Louise" },
                    "members": [ { "type": "way", "geometry": [ { "lat": 0, "lon": 0 }, { "lat": 1, "lon": 0 } ] },
                                 { "type": "way", "geometry": [ { "lat": 1, "lon": 0 }, { "lat": 0, "lon": 0 } ] } ] } ]
                """;

        OsmFeatures.Result result = OsmFeatures.rows(json.readTree(elements));
        List<OsmFeatures.Row> rows = result.rows();

        assertEquals(4, rows.size(), "the building that is not a closed ring is left out");
        OsmFeatures.Row loop = rows.get(0);
        assertEquals("road", loop.kind());
        assertEquals("osm:way/1", loop.ref());
        assertEquals("service", loop.details().get("class"));
        assertEquals(6.5, loop.details().get("width_m"));
        assertFalse(loop.buildArea());
        assertEquals(2.0, rows.get(1).details().get("width_m"), "a footway with no width is drawn 2 m wide");
        assertTrue(rows.get(2).buildArea());
        assertEquals("water", rows.get(3).kind());
        assertEquals(2, rows.get(3).lines().size());
        assertEquals("Lake Louise", rows.get(3).name());
    }

    @Test
    void theSeaTestPoint_isOnTheRightOfTheShoreline() {
        // A shoreline heading north. Land is on the left (west), so the sea is east of it.
        List<double[]> points = OsmFeatures.seaTestPoints(
                List.of(List.of(new double[] {-122.5, 47.0}, new double[] {-122.5, 47.01})), Math.cos(Math.toRadians(47)));

        assertEquals(1, points.size());
        assertTrue(points.getFirst()[0] > -122.5);
    }
}

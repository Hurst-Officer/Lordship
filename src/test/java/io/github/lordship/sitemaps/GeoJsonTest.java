package io.github.lordship.sitemaps;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GeoJsonTest {

    @Test
    void parts_shouldReadAPolygonsOuterRing() {
        // Arrange -- what ST_AsGeoJSON(outline, 7) returns
        String json = "{\"type\":\"Polygon\",\"coordinates\":"
                + "[[[-122.636,47.54],[-122.6358,47.54],[-122.6358,47.5398],[-122.636,47.5398],[-122.636,47.54]]]}";

        // Act
        List<double[][]> parts = GeoJson.parts(json);

        // Assert
        assertEquals(1, parts.size());
        assertEquals(5, parts.get(0).length);
        assertEquals(-122.636, parts.get(0)[0][0], 1e-9);
        assertEquals(47.54, parts.get(0)[0][1], 1e-9);
    }

    @Test
    void parts_shouldReadALineString() {
        String json = "{\"type\":\"LineString\",\"coordinates\":[[-122.64,47.54],[-122.63,47.54]]}";

        List<double[][]> parts = GeoJson.parts(json);

        assertEquals(1, parts.size());
        assertEquals(2, parts.get(0).length);
        assertEquals(-122.63, parts.get(0)[1][0], 1e-9);
    }

    @Test
    void parts_shouldReadEveryPieceOfAMultiPolygon() {
        String json = "{\"type\":\"MultiPolygon\",\"coordinates\":["
                + "[[[0,0],[1,0],[1,1],[0,0]]],"
                + "[[[5,5],[6,5],[6,6],[5,5]]]]}";

        List<double[][]> parts = GeoJson.parts(json);

        assertEquals(2, parts.size());
        assertEquals(5.0, parts.get(1)[0][0], 1e-9);
    }

    @Test
    void parts_shouldGiveNothing_whenThereIsNoGeometry() {
        assertTrue(GeoJson.parts(null).isEmpty());
        assertTrue(GeoJson.parts("{\"type\":\"Point\"}").isEmpty());
    }

    @Test
    void first_shouldGiveTheOuterRing() {
        String json = "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[2,0],[2,2],[0,0]]]}";

        assertEquals(4, GeoJson.first(json).length);
        assertNull(GeoJson.first(null));
    }
}

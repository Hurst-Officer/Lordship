package io.github.lordship.instruments;

import io.github.lordship.sitemaps.ParkMap;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** What the page chooses to draw out of a park's stored shapes. */
public class LotMapPageFromParkTest {

    private static final UUID PROPERTY = UUID.randomUUID();
    private static final UUID LOT_1 = UUID.randomUUID();
    private static final UUID LOT_2 = UUID.randomUUID();

    @Test
    void draw_shouldHighlightTheTenantsLot() {
        String svg = LotMapPage.draw(park(List.of()), LOT_2);

        assertTrue(svg.contains(">2</text>"));
        assertTrue(svg.contains(">Bell Hollow</text>"));
        assertTrue(svg.contains(">Lot 2"));
    }

    @Test
    void draw_shouldDrawTheTenantsHome_andLeaveTheNeighboursOff() {
        // Arrange -- a home on each lot
        ParkMap map = park(List.of(
                building(LOT_1, "{\"type\":\"Polygon\",\"coordinates\":[[[-122.6359,47.5399],[-122.63585,47.5399],[-122.63585,47.53985],[-122.6359,47.53985]]]}"),
                building(LOT_2, "{\"type\":\"Polygon\",\"coordinates\":[[[-122.6357,47.5399],[-122.63565,47.5399],[-122.63565,47.53985],[-122.6357,47.53985]]]}")));

        // Act
        String svg = LotMapPage.draw(map, LOT_2);

        // Assert -- one home footprint on the page, and it is the tenant's
        assertEquals(1, svg.split("#d8cfae", -1).length - 1);
    }

    @Test
    void draw_shouldSkipALotWhoseShapeIsMissing() {
        ParkMap map = new ParkMap(PROPERTY, "Bell Hollow", null, List.of(), LocalDate.of(2026, 9, 29),
                List.of(new ParkMap.Outline(LOT_1, "1", ring(-122.6360)),
                        new ParkMap.Outline(LOT_2, "2", null)),
                List.of());

        String svg = LotMapPage.draw(map, LOT_1);

        assertTrue(svg.contains(">1</text>"));
        assertFalse(svg.contains(">2</text>"));
    }

    private static ParkMap park(List<ParkMap.Feature> features) {
        return new ParkMap(PROPERTY, "Bell Hollow", null,
                List.of("© OpenStreetMap contributors"), LocalDate.of(2026, 9, 29),
                List.of(new ParkMap.Outline(LOT_1, "1", ring(-122.6360)),
                        new ParkMap.Outline(LOT_2, "2", ring(-122.6358))),
                features);
    }

    private static ParkMap.Feature building(UUID lot, String geoJson) {
        return new ParkMap.Feature("building", null, geoJson, 0, lot);
    }

    private static String ring(double left) {
        double right = left + 0.00018;
        return "{\"type\":\"Polygon\",\"coordinates\":[[["
                + left + ",47.5400],[" + right + ",47.5400],["
                + right + ",47.53977],[" + left + ",47.53977],[" + left + ",47.5400]]]}";
    }
}

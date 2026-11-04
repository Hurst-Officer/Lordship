package io.github.lordship.sitemaps.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// How far Glade turns a park's map to show it "plat up".
// One pixel up the drawing lands at (-b, -e) on the map, in Web Mercator.
public class PlatTurnTest {

    @Test
    void aPlatDrawnNorthUp_needsNoTurn() {
        // one pixel up is one step north: (-b, -e) = (0, 1)
        assertEquals(0.0, SiteMapScreenService.platTurnDegrees(0.0, -1.0));
    }

    @Test
    void aPlatWithEastAtTheTop_turnsAQuarterToTheLeft() {
        // one pixel up is one step east: (-b, -e) = (1, 0)
        assertEquals(-90.0, SiteMapScreenService.platTurnDegrees(-1.0, 0.0));
    }

    @Test
    void aPlatWithWestAtTheTop_turnsAQuarterToTheRight() {
        assertEquals(90.0, SiteMapScreenService.platTurnDegrees(1.0, 0.0));
    }

    @Test
    void aPlatDrawnUpsideDown_turnsHalfway() {
        assertEquals(180.0, Math.abs(SiteMapScreenService.platTurnDegrees(0.0, 1.0)));
    }

    @Test
    void aSmallTilt_isKeptToATenthOfADegree() {
        // up the drawing points a little east of north
        double tilt = Math.toRadians(12.34);
        assertEquals(-12.3, SiteMapScreenService.platTurnDegrees(-Math.sin(tilt), -Math.cos(tilt)));
    }

    @Test
    void halfADegreeOrLess_countsAsNorthUp() {
        double tilt = Math.toRadians(0.3);
        assertEquals(0.0, SiteMapScreenService.platTurnDegrees(-Math.sin(tilt), -Math.cos(tilt)));
    }

    @Test
    void noMatrix_meansNoTurn() {
        assertNull(SiteMapScreenService.platTurnDegrees(null, -1.0));
        assertNull(SiteMapScreenService.platTurnDegrees(0.0, 0.0));
    }
}

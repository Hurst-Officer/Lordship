package io.github.lordship.sitemaps.internal;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Contour lines and hillshade from a grid of heights
class ElevationTest {

    // 200 x 100 pixels, rising 0.1 m per pixel to the east: 30 m to about 50 m
    private static float[] slope() {
        float[] grid = new float[200 * 100];
        for (int r = 0; r < 100; r++) {
            for (int c = 0; c < 200; c++) {
                grid[r * 200 + c] = 30f + c * 0.1f;
            }
        }
        return grid;
    }

    @Test
    void aSteadySlope_getsOneStraightContourPerLevel() {
        float[] grid = slope();

        int interval = Elevation.contourIntervalFt(grid);
        List<Elevation.Line> lines = Elevation.contourLines(grid, 200, 100, interval);

        // About 65 ft of rise: 5 ft steps give 13 lines, from 100 ft to 160 ft
        assertEquals(5, interval);
        assertEquals(13, lines.size());
        assertEquals(100.0, lines.getFirst().levelFt());
        assertEquals(160.0, lines.getLast().levelFt());
        // Each runs straight from top to bottom, one point per row
        for (Elevation.Line line : lines) {
            assertEquals(100, line.points().size());
            double x = line.points().getFirst()[0];
            assertTrue(line.points().stream().allMatch(p -> Math.abs(p[0] - x) < 1e-6));
        }
    }

    @Test
    void theHillshade_isAPictureTheSizeOfTheGrid() throws Exception {
        byte[] png = Elevation.hillshadePng(slope(), 200, 100, 1.0);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(200, image.getWidth());
        assertEquals(100, image.getHeight());
        assertEquals(255, image.getRGB(100, 50) >>> 24);
    }

    @Test
    void aGridWithNoHeights_isRefused() {
        Elevation.Box box = new Elevation.Box(47.1, -122.51, 47.11, -122.5);
        Elevation.GridRequest asked = Elevation.gridFor(box);
        float[] missing = new float[asked.width() * asked.height()];
        java.util.Arrays.fill(missing, -3.4e38f);

        assertThrows(IllegalArgumentException.class,
                () -> Elevation.build(box, asked, new HeightTiff.Grid(asked.width(), asked.height(), missing)));
    }

    @Test
    void aBigArea_isCappedAt2000Pixels() {
        Elevation.GridRequest asked = Elevation.gridFor(new Elevation.Box(47.1, -122.51, 47.12, -122.49));

        assertEquals(2000, Math.max(asked.width(), asked.height()));
    }

    @Test
    void aWideGrid_keepsContoursToThePark_andSpacesThemForThePark() {
        Elevation.Box park = new Elevation.Box(47.1234, -122.5409, 47.1280, -122.5354);
        Elevation.GridRequest asked = Elevation.gridFor(Elevation.grown(park, 800));
        int w = asked.width();
        int h = asked.height();
        float[] heights = new float[w * h];
        // A park rising about 6 m, in a bowl of hills about 300 m higher at the edges
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                double x = c / (double) w;
                double rim = Math.max(0, Math.hypot(x - .5, r / (double) h - .5) - .2);
                heights[r * w + c] = (float) (78 + 6 * x + 1200 * rim * rim);
            }
        }

        Elevation.Built built = Elevation.build(park, asked, new HeightTiff.Grid(w, h, heights));

        // 1 ft steps suit the park, even though the hills would ask for 50 ft steps
        assertEquals(1, built.summary().contourIntervalFt());
        assertTrue(built.summary().maxFt() > 1000, "the hillshade and heights cover the hills too");
        for (Elevation.Contour contour : built.contours()) {
            for (double[] p : contour.points()) {
                assertTrue(p[0] > park.west() - 0.001 && p[0] < park.east() + 0.001);
            }
        }
    }
}

package io.github.lordship.sitemaps.internal;

import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.Heights;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// The coarse height grid behind the property page's hills
class TerrainHeightsTest {

    // Heights in metres as the database keeps them: 32-bit little-endian floats, top row first
    private static byte[] grid(float... metres) {
        ByteBuffer buffer = ByteBuffer.allocate(metres.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float m : metres) {
            buffer.putFloat(m);
        }
        return buffer.array();
    }

    @Test
    void aSmallGrid_isKeptAsItIs_inFeet() {
        Heights h = SiteMapScreenService.coarseHeights(grid(100, 101, 102, 103), 2, 2, 96);

        assertEquals(2, h.columns());
        assertEquals(2, h.rows());
        assertEquals(Arrays.asList(328.1, 331.4, 334.6, 337.9), h.feet());
    }

    @Test
    void aWideGrid_isAveragedDownToTheColumnLimit() {
        // 4 x 2 with at most 2 columns: each coarse cell is a 2 x 2 block
        Heights h = SiteMapScreenService.coarseHeights(grid(
                10, 20, 30, 40,
                10, 20, 30, 40), 4, 2, 2);

        assertEquals(2, h.columns());
        assertEquals(1, h.rows());
        assertEquals(Arrays.asList(49.2, 114.8), h.feet());
    }

    @Test
    void missingHeights_areLeftOutOfTheAverage() {
        Heights h = SiteMapScreenService.coarseHeights(grid(10, Float.NaN, 30, Float.NaN), 2, 2, 1);

        // only 10 m and 30 m count: 20 m is 65.6 ft
        assertEquals(Arrays.asList(65.6), h.feet());
    }

    @Test
    void aCellWithNoHeightsAtAll_isNull() {
        Heights h = SiteMapScreenService.coarseHeights(grid(Float.NaN, Float.NaN, 10, 10), 2, 2, 96);

        assertNull(h.feet().get(0));
        assertNull(h.feet().get(1));
        assertEquals(32.8, h.feet().get(2));
    }

    @Test
    void anUnevenGrid_keepsItsLastPartialBlock() {
        // 5 across with at most 2 columns: blocks of 3, then a block of 2
        Heights h = SiteMapScreenService.coarseHeights(grid(1, 1, 1, 2, 2), 5, 1, 2);

        assertEquals(2, h.columns());
        assertEquals(Arrays.asList(3.3, 6.6), h.feet());
    }
}

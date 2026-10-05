package io.github.lordship.instruments;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LotMapDrawingTest {

    // A park near Port Orchard. Lots are 15 m wide and 25 m deep, in one row.
    private static final double LNG0 = -122.6360;
    private static final double LAT0 = 47.5400;

    @Test
    void draw_shouldHighlightTheTenantsLotAndNumberEveryOther() {
        // Arrange
        LotMapDrawing.Park park = park(6, "3");

        // Act
        String svg = LotMapDrawing.draw(park);

        // Assert -- every lot is numbered, so the tenant can see where they sit
        for (int number = 1; number <= 6; number++) {
            assertTrue(svg.contains(">" + number + "</text>"), "lot " + number + " is not labelled");
        }
        // and exactly one lot is filled and drawn heavily
        assertEquals(1, count(svg, "stroke-width=\"0.8\""));
        assertTrue(svg.contains("#f0dca8"));
    }

    @Test
    void draw_shouldSizeTheSheetInMillimetres_soThePrinterGetsRealUnits() {
        String svg = LotMapDrawing.draw(park(6, "1"));

        assertTrue(svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\""));
        assertTrue(svg.contains("width=\"170.9mm\""));
        assertTrue(svg.contains("height=\"230.4mm\""));
    }

    @Test
    void bestTurn_shouldFitMoreParkOnThePage_thanLeavingItNorthUp() {
        // Arrange -- six lots in an east-west row, which north-up wastes the page on
        List<LotMapDrawing.Lot> row = lots(6);
        LotMapDrawing.Projection to = new LotMapDrawing.Projection(LNG0, LAT0);

        // Act
        double turn = LotMapDrawing.bestTurn(row, to);

        // Assert -- the chosen angle draws the park bigger than north-up does
        assertTrue(scaleAt(row, to, turn) > scaleAt(row, to, 0) * 1.2,
                "turning gained too little: " + scaleAt(row, to, turn) + " vs " + scaleAt(row, to, 0));
    }

    @Test
    void draw_shouldDropALotNumber_thatCannotFitInsideItsOwnLot() {
        // Arrange -- an ordinary row plus one sliver of a lot, a third of a metre across
        List<LotMapDrawing.Lot> lots = new ArrayList<>(lots(6));
        double left = LNG0 + 6 * 0.0002;
        lots.add(new LotMapDrawing.Lot("999", new double[][] {
                {left, LAT0}, {left + 0.000004, LAT0},
                {left + 0.000004, LAT0 - 0.000004}, {left, LAT0 - 0.000004}}));

        LotMapDrawing.Park park = new LotMapDrawing.Park(
                "Bell Hollow", "3", lots, List.of(), List.of(), null, null,
                List.of("(c) OpenStreetMap contributors"), LocalDate.of(2026, 9, 29));

        // Act
        String svg = LotMapDrawing.draw(park);

        // Assert -- the ordinary lots keep their numbers, the sliver does not
        assertTrue(svg.contains(">4</text>"));
        assertFalse(svg.contains(">999</text>"), "a number wider than its lot should not be drawn");
    }

    @Test
    void draw_shouldFitTheWholePark_whenTheLotsAreStillReadable() {
        // Arrange -- six lots across 90 m fit a letter page comfortably
        String svg = LotMapDrawing.draw(park(6, "3"));

        // Assert -- nothing is cut off, so nothing is faded
        assertFalse(svg.contains("fade-left"));
    }

    @Test
    void draw_shouldRunOffTheEdgeAndFade_whenTheParkIsTooLongToFit() {
        // Arrange -- 120 lots in a row is 1.8 km; whole-park would be specks
        String svg = LotMapDrawing.draw(park(120, "60"));

        // Assert -- drawn at a readable size, faded where it leaves the page
        assertTrue(svg.contains("fade-left"));
        assertTrue(svg.contains("fade-bottom"));
        assertTrue(svg.contains(">60</text>"), "the tenant's lot must still be on the page");
    }

    @Test
    void draw_shouldPutTheParkNameAtTheCornerOfTheLots() {
        String svg = LotMapDrawing.draw(park(6, "3"));

        assertTrue(svg.contains("font-weight=\"bold\" fill=\"#111\">Bell Hollow</text>"));
    }

    @Test
    void draw_shouldPrintTheCreditLine_becauseTheDataLicenceRequiresIt() {
        String svg = LotMapDrawing.draw(park(6, "2"));

        assertTrue(svg.contains("OpenStreetMap"));
    }

    @Test
    void draw_shouldEscapeEveryPieceOfTextItPrints() {
        // Arrange -- a park name with markup in it
        LotMapDrawing.Park park = new LotMapDrawing.Park(
                "Bell & <script>Hollow</script>", "1", lots(2), List.of(), List.of(),
                null, null, List.of("(c) OpenStreetMap contributors"), LocalDate.of(2026, 9, 29));

        // Act
        String svg = LotMapDrawing.draw(park);

        // Assert
        assertFalse(svg.contains("<script>"));
        assertTrue(svg.contains("Bell &amp; &lt;script&gt;"));
    }

    @Test
    void draw_shouldRefuseALotThatIsNotInThisPark() {
        // Arrange -- a tenancy pointing at the wrong park is a bug, not a blank page
        LotMapDrawing.Park park = park(6, "99");

        // Act / Assert
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> LotMapDrawing.draw(park));
        assertTrue(thrown.getMessage().contains("99"));
    }

    @Test
    void labelPoint_shouldLandInsideAnLShapedLot() {
        // Arrange -- an L: the average of the corners falls in the notch
        double[][] ring = {
                {0, 0}, {0, 10}, {6, 10}, {6, 4}, {10, 4}, {10, 0}
        };

        // Act
        double[] at = LotMapDrawing.labelPoint(ring);

        // Assert -- on the widest horizontal slice, which is inside the shape
        assertTrue(at[0] >= 0 && at[0] <= 10, "x " + at[0] + " is outside the lot");
        assertTrue(at[1] >= 0 && at[1] <= 10, "y " + at[1] + " is outside the lot");
    }

    // ---- the park ------------------------------------------------------------

    private static LotMapDrawing.Park park(int lots, String tenant) {
        double[][] road = {
                {LNG0 - 0.0002, LAT0 + 0.00030},
                {LNG0 + 0.0002 * lots, LAT0 + 0.00030}
        };
        return new LotMapDrawing.Park(
                "Bell Hollow",
                tenant,
                lots(lots),
                List.of(new LotMapDrawing.Line("Alder Loop", 8, road)),
                List.of(),
                null,
                null,
                List.of("(c) OpenStreetMap contributors"),
                LocalDate.of(2026, 9, 29));
    }

    /** A row of rectangular lots, numbered from 1. */
    private static List<LotMapDrawing.Lot> lots(int count) {
        List<LotMapDrawing.Lot> lots = new ArrayList<>(count);
        double wide = 0.0002;   // about 15 m of longitude here
        double deep = 0.000225; // about 25 m of latitude
        for (int i = 0; i < count; i++) {
            double left = LNG0 + i * wide;
            double[][] ring = {
                    {left, LAT0},
                    {left + wide * 0.95, LAT0},
                    {left + wide * 0.95, LAT0 - deep},
                    {left, LAT0 - deep}
            };
            lots.add(new LotMapDrawing.Lot(String.valueOf(i + 1), ring));
        }
        return lots;
    }

    /** How big the park draws on the sheet at one angle, in mm per metre. */
    private static double scaleAt(List<LotMapDrawing.Lot> lots, LotMapDrawing.Projection to, double turn) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (LotMapDrawing.Lot lot : lots) {
            for (double[] point : lot.ring()) {
                double x = to.x(point[0], point[1]);
                double y = to.y(point[0], point[1]);
                double tx = x * Math.cos(turn) - y * Math.sin(turn);
                double ty = x * Math.sin(turn) + y * Math.cos(turn);
                minX = Math.min(minX, tx); maxX = Math.max(maxX, tx);
                minY = Math.min(minY, ty); maxY = Math.max(maxY, ty);
            }
        }
        return Math.min(175.9 / (maxX - minX), 237.4 / (maxY - minY));
    }

    private static int count(String text, String needle) {
        int found = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            found++;
            at = text.indexOf(needle, at + needle.length());
        }
        return found;
    }
}

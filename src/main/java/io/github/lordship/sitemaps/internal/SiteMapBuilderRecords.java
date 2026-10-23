package io.github.lordship.sitemaps.internal;

import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the map builder sends and gets back.
 *
 * <p>Two kinds of position travel here:
 * <ul>
 *   <li>vertices: corners on the plat drawing, in drawing pixels, [x, y]</li>
 *   <li>outline: corners on the ground, [longitude, latitude], not closed (the first corner is not repeated)</li>
 * </ul>
 */
public final class SiteMapBuilderRecords {

    private SiteMapBuilderRecords() { }

    // ---- GET /api/sitemaps/{propertyId}/builder --------------------------------

    /**
     * Everything the builder needs to start on a park.
     *
     * @param placement null when the park has never been placed on the map
     * @param lots      every live lot of the park, placed or not
     */
    public record BuilderResponse(
            UUID propertyId,
            String propertyCode,
            String propertyName,
            String address,
            Double latitude,
            Double longitude,
            Imagery imagery,
            Placement placement,
            List<BuilderLot> lots
    ) { }

    /** The satellite tiles to lay the drawing over. tiles has {z}, {x} and {y} in it. */
    public record Imagery(String tiles, int maxZoom, String credit) { }

    /**
     * How the drawing was pinned to the globe at the last save.
     *
     * @param imageToWorld the pixel to Web Mercator matrix, as the builder saved it
     * @param hasDrawing   true when the drawing itself was kept and can be fetched
     */
    public record Placement(
            int platWidth,
            int platHeight,
            String platFileName,
            @JsonRawValue String imageToWorld,
            boolean hasDrawing,
            OffsetDateTime placedAt
    ) { }

    /**
     * One lot as the builder sees it.
     *
     * @param vertices its corners on the drawing, or null when it was never drawn
     * @param outline  its corners on the ground, or null when it is not on the map
     */
    public record BuilderLot(
            UUID lotId,
            String lotNumber,
            List<List<Double>> vertices,
            List<List<Double>> outline
    ) { }

    // ---- POST /api/sitemaps/{propertyId}/builder -------------------------------

    /**
     * A finished map. Every lot listed is saved. Live lots of the park that are
     * left out keep their records but lose their place on the map.
     *
     * @param imageToWorld how the drawing sits on the globe, saved as given
     * @param imagery      which satellite imagery the drawing was lined up against
     */
    public record CommitRequest(
            @NotNull Integer platWidth,
            @NotNull Integer platHeight,
            String platFileName,
            Map<String, Object> imageToWorld,
            String imagery,
            List<CommitLot> lots
    ) {
        public CommitRequest {
            lots = (lots == null) ? List.of() : List.copyOf(lots);
        }
    }

    /** One lot on the finished map. Matched to the park's lots by its number. */
    public record CommitLot(
            String lotNumber,
            List<List<Double>> vertices,
            List<List<Double>> outline
    ) { }

    /**
     * What the save did.
     *
     * @param created  lot numbers that were new to the park and were added
     * @param unplaced live lots of the park that are not on the map after this save
     */
    public record CommitResponse(
            int placed,
            List<String> created,
            List<String> unplaced,
            long totalSquareFeet
    ) { }

    // ---- GET /api/sitemaps/geocode ------------------------------------------------

    public record GeocodeResponse(boolean found, Double longitude, Double latitude, String label) { }
}

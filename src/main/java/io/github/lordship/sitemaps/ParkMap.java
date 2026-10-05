package io.github.lordship.sitemaps;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One park's map, read in one go: the shapes a lease page needs.
 *
 * <p>Geometry arrives as GeoJSON text rather than as PostGIS objects, so
 * nothing outside this package needs a geometry library. {@link GeoJson} turns
 * it into points.
 */
public record ParkMap(
        UUID propertyId,
        String communityName,
        String boundary,          // GeoJSON, or null outside Washington
        List<String> credits,     // licence lines the printed map must carry
        LocalDate placedOn,
        List<Outline> lots,
        List<Feature> features
) {
    public ParkMap {
        credits = List.copyOf(credits);
        lots = List.copyOf(lots);
        features = List.copyOf(features);
    }

    /** One lot's shape. */
    public record Outline(UUID lotId, String lotNumber, String geoJson) {}

    /**
     * Anything else on the map.
     *
     * @param widthMetres the real width of a road or stream; 0 for an area
     * @param lotId set on a building that stands on one lot
     */
    public record Feature(String kind, String name, String geoJson, double widthMetres, UUID lotId) {}
}

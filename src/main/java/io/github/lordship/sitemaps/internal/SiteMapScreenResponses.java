package io.github.lordship.sitemaps.internal;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.util.List;
import java.util.UUID;

// What GET /api/sitemaps/... returns. Geometry is real GeoJSON in the body, not a string.
public final class SiteMapScreenResponses {

    private SiteMapScreenResponses() { }

    // One park's small drawing for the global map.
    // hull and lots are SVG paths in metres east and south of the middle point.
    public record FootprintResponse(
            UUID propertyId,
            int lotCount,
            double middleLongitude,
            double middleLatitude,
            double sizeMetres,
            String hull,
            String lots
    ) { }

    // One park's map for the property screen
    public record SiteMapResponse(
            UUID propertyId,
            List<LotShape> lots,
            List<FeatureShape> features
    ) { }

    public record LotShape(
            UUID lotId,
            String lotNumber,
            @JsonRawValue String outline,
            double labelLongitude,
            double labelLatitude
    ) { }

    // widthMetres is the real width of a road or stream; 0 for everything else
    public record FeatureShape(
            String kind,
            String name,
            @JsonRawValue String geometry,
            double widthMetres
    ) { }
}

package io.github.lordship.sitemaps.internal;

import java.util.UUID;

// Rows read by SiteMapScreenRepository. Geometry is GeoJSON text.
final class SiteMapScreenRows {

    private SiteMapScreenRows() { }

    // sql: property_footprint in V6. The *Svg columns are SVG paths in metres around the middle.
    record FootprintRow(UUID propertyId, int lotCount, double middleLongitude, double middleLatitude, double sizeMetres,
                        String hullSvg, String lotsSvg) { }

    // sql: lot_outline joined to lot, with the point where the lot number goes
    record LotShapeRow(UUID lotId, String lotNumber, String outline, double labelLongitude, double labelLatitude) { }

    // sql: map_feature
    record FeatureShapeRow(String kind, String name, String geometry, double widthMetres) { }
}

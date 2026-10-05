package io.github.lordship.instruments;

import io.github.lordship.sitemaps.GeoJson;
import io.github.lordship.sitemaps.ParkMap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns a park's stored shapes into the drawing the lease prints.
 *
 * <p>Two jobs only: decide what goes on the page, and hand the points to
 * {@link LotMapDrawing}. Pure, so the choices are testable without a database.
 *
 * <p>What goes on: every lot, the roads and water flagged for printing, the
 * park boundary, and the tenant's own home. Other homes are left off. They are
 * traced from imagery that can be years old, and forty stale rectangles on a
 * signed lease is forty arguments.
 */
public final class LotMapPage {

    private LotMapPage() {}

    public static String draw(ParkMap map, UUID tenantLotId) {
        String tenantNumber = null;
        List<LotMapDrawing.Lot> lots = new ArrayList<>();

        for (ParkMap.Outline outline : map.lots()) {
            double[][] ring = GeoJson.first(outline.geoJson());
            if (ring == null) {
                continue;
            }
            lots.add(new LotMapDrawing.Lot(outline.lotNumber(), ring));
            if (outline.lotId().equals(tenantLotId)) {
                tenantNumber = outline.lotNumber();
            }
        }

        List<LotMapDrawing.Line> roads = new ArrayList<>();
        List<LotMapDrawing.Area> water = new ArrayList<>();
        LotMapDrawing.Area home = null;

        for (ParkMap.Feature feature : map.features()) {
            switch (feature.kind()) {
                case "road", "stream" -> {
                    for (double[][] line : GeoJson.parts(feature.geoJson())) {
                        roads.add(new LotMapDrawing.Line(feature.name(), feature.widthMetres(), line));
                    }
                }
                case "water", "coastline" -> {
                    for (double[][] ring : GeoJson.parts(feature.geoJson())) {
                        water.add(new LotMapDrawing.Area(ring));
                    }
                }
                case "building" -> {
                    if (tenantLotId.equals(feature.lotId()) && home == null) {
                        double[][] ring = GeoJson.first(feature.geoJson());
                        if (ring != null) {
                            home = new LotMapDrawing.Area(ring);
                        }
                    }
                }
                default -> { }
            }
        }

        double[][] boundary = GeoJson.first(map.boundary());

        return LotMapDrawing.draw(new LotMapDrawing.Park(
                map.communityName(),
                tenantNumber,
                lots,
                roads,
                water,
                home,
                boundary == null ? null : new LotMapDrawing.Area(boundary),
                map.credits(),
                map.placedOn()));
    }
}

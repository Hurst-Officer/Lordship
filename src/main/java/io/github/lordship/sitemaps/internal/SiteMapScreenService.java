package io.github.lordship.sitemaps.internal;

import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.FeatureShape;
import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.FootprintResponse;
import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.Heights;
import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.LotShape;
import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.SiteMapResponse;
import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.TerrainResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Builds the map screens' answers. Read-only.
@Service
@Transactional(readOnly = true)
public class SiteMapScreenService {

    private final SiteMapScreenRepository repository;

    public SiteMapScreenService(SiteMapScreenRepository repository) {
        this.repository = repository;
    }

    // Not read-only: drawings that went out of date are rebuilt first.
    @Transactional
    public List<FootprintResponse> footprints() {
        repository.refreshStaleFootprints();
        return repository.findFootprints().stream()
                .map(f -> new FootprintResponse(
                        f.propertyId(), f.lotCount(), f.middleLongitude(), f.middleLatitude(), f.sizeMetres(),
                        f.hullSvg(), f.lotsSvg()))
                .toList();
    }

    // Empty when the park has never been placed on a map.
    public Optional<SiteMapResponse> siteMap(UUID propertyId) {
        if (!repository.hasSiteMap(propertyId)) {
            return Optional.empty();
        }
        List<LotShape> lots = repository.findLotShapes(propertyId).stream()
                .map(l -> new LotShape(l.lotId(), l.lotNumber(), l.outline(), l.labelLongitude(), l.labelLatitude()))
                .toList();
        List<FeatureShape> features = repository.findFeatureShapes(propertyId).stream()
                .map(f -> new FeatureShape(f.kind(), f.name(), f.geometry(), f.widthMetres(),
                        f.elevationFt(), f.contourIndex()))
                .toList();
        return Optional.of(new SiteMapResponse(propertyId, lots, features));
    }

    // The coarse grid has at most this many columns. Enough for the hills along the bottom of the page.
    static final int TERRAIN_COLUMNS = 96;

    // Empty when the park's heights were never pulled.
    public Optional<TerrainResponse> terrain(UUID propertyId) {
        return repository.findTerrain(propertyId).map(t -> new TerrainResponse(
                t.minFt(), t.maxFt(), t.contourIntervalFt(),
                t.west(), t.south(), t.east(), t.north(),
                coarseHeights(t.grid(), t.width(), t.height(), TERRAIN_COLUMNS)));
    }

    /**
     * Shrinks the height grid to at most maxColumns across, keeping its shape.
     * Each coarse cell is the average of the heights under it, in feet to 0.1.
     * A cell with no heights under it is null.
     */
    static Heights coarseHeights(byte[] grid, int width, int height, int maxColumns) {
        ByteBuffer buffer = ByteBuffer.wrap(grid).order(ByteOrder.LITTLE_ENDIAN);
        int step = Math.max(1, (int) Math.ceil(width / (double) maxColumns));
        int columns = (int) Math.ceil(width / (double) step);
        int rows = (int) Math.ceil(height / (double) step);

        List<Double> feet = new ArrayList<>(columns * rows);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                double sum = 0;
                int count = 0;
                for (int r = row * step; r < Math.min(height, (row + 1) * step); r++) {
                    for (int c = column * step; c < Math.min(width, (column + 1) * step); c++) {
                        float metres = buffer.getFloat((r * width + c) * 4);
                        if (!Float.isNaN(metres)) {
                            sum += metres;
                            count++;
                        }
                    }
                }
                feet.add(count == 0 ? null : Math.round(sum / count * Elevation.FT_PER_M * 10) / 10.0);
            }
        }
        return new Heights(columns, rows, feet);
    }
}

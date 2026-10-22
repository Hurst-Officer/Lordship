package io.github.lordship.sitemaps.internal;

import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.FeatureShape;
import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.FootprintResponse;
import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.LotShape;
import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.SiteMapResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public List<FootprintResponse> footprints() {
        return repository.findFootprints().stream()
                .map(f -> new FootprintResponse(f.propertyId(), f.outline(), f.lotCount()))
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
                .map(f -> new FeatureShape(f.kind(), f.name(), f.geometry(), f.widthMetres()))
                .toList();
        return Optional.of(new SiteMapResponse(propertyId, lots, features));
    }
}

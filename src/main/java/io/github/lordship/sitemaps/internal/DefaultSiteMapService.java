package io.github.lordship.sitemaps.internal;

import io.github.lordship.sitemaps.ParkMap;
import io.github.lordship.sitemaps.SiteMapService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class DefaultSiteMapService implements SiteMapService {

    private final SiteMapRepository repository;

    public DefaultSiteMapService(SiteMapRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<ParkMap> findByProperty(UUID propertyId) {
        return findMap(propertyId, null, null);
    }

    @Override
    public Optional<ParkMap> findForLease(UUID propertyId, UUID lotId, String communityName) {
        return findMap(propertyId, lotId, communityName).filter(map -> isDrawn(map, lotId));
    }

    /** Whether the map holds a shape for this lot. Without one there is no lot to highlight. */
    private static boolean isDrawn(ParkMap map, UUID lotId) {
        return map.lots().stream().anyMatch(lot -> lot.lotId().equals(lotId));
    }

    private Optional<ParkMap> findMap(UUID propertyId, UUID lotId, String communityName) {
        return repository.findSiteMap(propertyId).map(placed -> {
            List<ParkMap.Outline> lots = repository.findOutlines(propertyId);
            List<ParkMap.Feature> features = repository.findPrintable(propertyId, lotId);
            return new ParkMap(propertyId, communityName, placed.boundary(), placed.credits(),
                    placed.placedOn(), lots, features);
        });
    }
}

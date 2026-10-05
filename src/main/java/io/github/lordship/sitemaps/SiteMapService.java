package io.github.lordship.sitemaps;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads a park's map.
 *
 * <p>Empty means there is no map to draw. Most parks start that way, so empty is
 * an ordinary answer rather than a refusal: screens ask with
 * {@link #findByProperty}, and a lease that prints a map asks with
 * {@link #findForLease} and leaves the page blank in a preview when the answer
 * is empty. Generation is where a missing map stops the work.
 */
public interface SiteMapService {

    Optional<ParkMap> findByProperty(UUID propertyId);

    /**
     * The map a lease would print for one lot.
     *
     * <p>Empty when the park has never been placed, and empty when the map holds
     * no shape for this lot. Both mean the same thing to a lease -- there is
     * nothing to draw -- so they are not told apart here.
     */
    Optional<ParkMap> findForLease(UUID propertyId, UUID lotId, String communityName);
}

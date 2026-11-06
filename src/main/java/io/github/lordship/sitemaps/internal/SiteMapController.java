package io.github.lordship.sitemaps.internal;

import io.github.lordship.identity.AgentPrincipal;
import io.github.lordship.shared.PropertyScope;
import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.FootprintResponse;
import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.SiteMapResponse;
import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.TerrainResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Maps for Glade's screens. Geometry is GeoJSON in [longitude, latitude] order.
 *
 * <p>These answers are large and rarely change, so each carries a fingerprint (an ETag) and
 * "no-cache": the browser keeps its copy and asks the server whether it is still good.
 * When nothing changed, Spring answers 304 with no body and the browser uses its copy.
 *
 * <p>A park outside the agent's scope (see PropertyScope) is treated as if it did not exist.
 */
@RestController
@RequestMapping("/api/sitemaps")
public class SiteMapController {

    private final SiteMapScreenService siteMapScreenService;

    public SiteMapController(SiteMapScreenService siteMapScreenService) {
        this.siteMapScreenService = siteMapScreenService;
    }

    // One simplified outline per park the agent may see, for the global map. Parks with no lot shapes are left out.
    @PreAuthorize("hasAuthority('properties:view')")
    @GetMapping("/footprints")
    public ResponseEntity<List<FootprintResponse>> footprints(Authentication authentication) {
        PropertyScope scope = AgentPrincipal.scopeOf(authentication);
        return cached(siteMapScreenService.footprints().stream()
                .filter(footprint -> scope.allows(footprint.propertyId()))
                .toList());
    }

    // One park's lots and surroundings. 404 when the park has never been placed on a map.
    @PreAuthorize("hasAuthority('properties:view')")
    @GetMapping("/{propertyId}")
    public ResponseEntity<SiteMapResponse> siteMap(@PathVariable UUID propertyId, Authentication authentication) {
        if (!AgentPrincipal.scopeOf(authentication).allows(propertyId)) {
            return ResponseEntity.notFound().build();
        }
        return siteMapScreenService.siteMap(propertyId)
                .map(SiteMapController::cached)
                .orElse(ResponseEntity.notFound().build());
    }

    // The ground around one park: its height range, the box its hillshade covers, and a coarse
    // grid of heights. The picture itself is GET /{propertyId}/hillshade.png.
    // 404 when the park's heights were never pulled.
    @PreAuthorize("hasAuthority('properties:view')")
    @GetMapping("/{propertyId}/terrain")
    public ResponseEntity<TerrainResponse> terrain(@PathVariable UUID propertyId, Authentication authentication) {
        if (!AgentPrincipal.scopeOf(authentication).allows(propertyId)) {
            return ResponseEntity.notFound().build();
        }
        return siteMapScreenService.terrain(propertyId)
                .map(SiteMapController::cached)
                .orElse(ResponseEntity.notFound().build());
    }

    // A 200 the browser may keep, checking with the server before each reuse.
    // The fingerprint is the answer's hash: the records hash their values, so the same map gives the same tag.
    static <T> ResponseEntity<T> cached(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().cachePrivate())
                .eTag(Integer.toHexString(body.hashCode()))
                .body(body);
    }
}

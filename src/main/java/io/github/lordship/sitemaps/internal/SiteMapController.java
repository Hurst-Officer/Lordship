package io.github.lordship.sitemaps.internal;

import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.FootprintResponse;
import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.SiteMapResponse;
import io.github.lordship.sitemaps.internal.SiteMapScreenResponses.TerrainResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Maps for Glade's screens. Geometry is GeoJSON in [longitude, latitude] order.
 */
@RestController
@RequestMapping("/api/sitemaps")
public class SiteMapController {

    private final SiteMapScreenService siteMapScreenService;

    public SiteMapController(SiteMapScreenService siteMapScreenService) {
        this.siteMapScreenService = siteMapScreenService;
    }

    // One simplified outline per park, for the global map. Parks with no lot shapes are left out.
    @PreAuthorize("hasAuthority('properties:view')")
    @GetMapping("/footprints")
    public ResponseEntity<List<FootprintResponse>> footprints() {
        return ResponseEntity.ok(siteMapScreenService.footprints());
    }

    // One park's lots and surroundings. 404 when the park has never been placed on a map.
    @PreAuthorize("hasAuthority('properties:view')")
    @GetMapping("/{propertyId}")
    public ResponseEntity<SiteMapResponse> siteMap(@PathVariable UUID propertyId) {
        return siteMapScreenService.siteMap(propertyId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // The ground around one park: its height range, the box its hillshade covers, and a coarse
    // grid of heights. The picture itself is GET /{propertyId}/hillshade.png.
    // 404 when the park's heights were never pulled.
    @PreAuthorize("hasAuthority('properties:view')")
    @GetMapping("/{propertyId}/terrain")
    public ResponseEntity<TerrainResponse> terrain(@PathVariable UUID propertyId) {
        return siteMapScreenService.terrain(propertyId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}

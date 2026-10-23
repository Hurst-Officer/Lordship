package io.github.lordship.sitemaps.internal;

import io.github.lordship.properties.PropertyService;
import io.github.lordship.sitemaps.internal.SurroundingsService.Status;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A park's surroundings: roads, water, buildings, property lines and the height of the ground,
 * pulled from outside sources. Saving a map in the builder starts a pull on its own.
 */
@RestController
@RequestMapping("/api/sitemaps")
public class SurroundingsController {

    private final SurroundingsService surroundingsService;
    private final PropertyService propertyService;

    public SurroundingsController(SurroundingsService surroundingsService, PropertyService propertyService) {
        this.surroundingsService = surroundingsService;
        this.propertyService = propertyService;
    }

    // What the last pull is doing or did. 404 when there is no such property.
    @PreAuthorize("hasAuthority('sitemaps:edit')")
    @GetMapping("/{propertyId}/surroundings")
    public ResponseEntity<Status> status(@PathVariable UUID propertyId) {
        if (propertyService.findByPropertyId(propertyId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(surroundingsService.status(propertyId));
    }

    // Pulls again, in the background. 202 with the status to poll.
    // 409 when the park has no lots on the map yet. 404 when there is no such property.
    @PreAuthorize("hasAuthority('sitemaps:edit')")
    @PostMapping("/{propertyId}/surroundings")
    public ResponseEntity<Status> refresh(@PathVariable UUID propertyId) {
        if (propertyService.findByPropertyId(propertyId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(surroundingsService.refresh(propertyId));
    }

    // The shaded picture of the ground. 404 when the heights were never pulled.
    @PreAuthorize("hasAuthority('properties:view')")
    @GetMapping(value = "/{propertyId}/hillshade.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> hillshade(@PathVariable UUID propertyId) {
        return surroundingsService.hillshade(propertyId)
                .map(png -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noCache())
                        .body(png))
                .orElse(ResponseEntity.notFound().build());
    }
}

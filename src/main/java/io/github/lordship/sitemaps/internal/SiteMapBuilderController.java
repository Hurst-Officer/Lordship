package io.github.lordship.sitemaps.internal;

import io.github.lordship.sitemaps.internal.SiteMapBuilderRecords.BuilderResponse;
import io.github.lordship.sitemaps.internal.SiteMapBuilderRecords.CommitRequest;
import io.github.lordship.sitemaps.internal.SiteMapBuilderRecords.CommitResponse;
import io.github.lordship.sitemaps.internal.SiteMapBuilderRecords.GeocodeResponse;
import io.github.lordship.sitemaps.internal.SiteMapBuilderService.PlatUpload;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/**
 * The map builder in Glade. Everything here needs sitemaps:edit.
 *
 * <p>Saving a map can add lots: a number on the drawing that the park does not
 * have yet becomes a new lot. That is part of building a park's map, so it needs
 * no separate lots:create.
 */
@RestController
@RequestMapping("/api/sitemaps")
public class SiteMapBuilderController {

    private final SiteMapBuilderService builderService;
    private final Geocoder geocoder;

    public SiteMapBuilderController(SiteMapBuilderService builderService, Geocoder geocoder) {
        this.builderService = builderService;
        this.geocoder = geocoder;
    }

    // The park, its lots and its last placement. 404 when there is no such property.
    @PreAuthorize("hasAuthority('sitemaps:edit')")
    @GetMapping("/{propertyId}/builder")
    public ResponseEntity<BuilderResponse> open(@PathVariable UUID propertyId) {
        return builderService.open(propertyId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // The drawing kept with the last save. 404 when none was kept.
    @PreAuthorize("hasAuthority('sitemaps:edit')")
    @GetMapping("/{propertyId}/builder/plat")
    public ResponseEntity<byte[]> plat(@PathVariable UUID propertyId) {
        return builderService.plat(propertyId)
                .map(stored -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(stored.file().contentType()))
                        .cacheControl(CacheControl.noStore())
                        .body(stored.content()))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Saves a finished map. Sent as two parts:
     * "commit", the map as JSON, and "plat", the drawing. Leave "plat" out to keep the last drawing.
     * 400 lists every problem at once. 404 when there is no such property.
     */
    @PreAuthorize("hasAuthority('sitemaps:edit')")
    @PostMapping(path = "/{propertyId}/builder", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CommitResponse> commit(@PathVariable UUID propertyId,
                                                 @Valid @RequestPart("commit") CommitRequest request,
                                                 @RequestPart(value = "plat", required = false) MultipartFile plat) {
        PlatUpload upload = null;
        if (plat != null && !plat.isEmpty()) {
            try {
                upload = new PlatUpload(plat.getOriginalFilename(), plat.getContentType(), plat.getBytes());
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read the uploaded drawing", e);
            }
        }
        return builderService.commit(propertyId, request, upload)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // The search box: an address, a town, or "latitude, longitude"
    @PreAuthorize("hasAuthority('sitemaps:edit')")
    @GetMapping("/geocode")
    public ResponseEntity<GeocodeResponse> geocode(@RequestParam("q") String query) {
        return ResponseEntity.ok(geocoder.find(query)
                .map(place -> new GeocodeResponse(true, place.longitude(), place.latitude(), place.label()))
                .orElse(new GeocodeResponse(false, null, null, null)));
    }
}

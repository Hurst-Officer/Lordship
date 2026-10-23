package io.github.lordship.sitemaps.internal;

import io.github.lordship.properties.PropertyService;
import io.github.lordship.sitemaps.internal.FeatureEditRecords.CreateRequest;
import io.github.lordship.sitemaps.internal.FeatureEditRecords.CreatedResponse;
import io.github.lordship.sitemaps.internal.FeatureEditRecords.DeletedResponse;
import io.github.lordship.sitemaps.internal.FeatureEditRecords.FeaturesResponse;
import io.github.lordship.sitemaps.internal.FeatureEditRecords.RenameRequest;
import io.github.lordship.sitemaps.internal.FeatureEditRecords.RenamedResponse;
import io.github.lordship.sitemaps.internal.FeatureEditRecords.ShapeRequest;
import io.github.lordship.sitemaps.internal.FeatureEditRecords.ShapedResponse;
import io.github.lordship.sitemaps.internal.FeatureEditRecords.UnhiddenResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Features mode in the map builder: buildings, lights and roads changed by hand.
 * Everything here needs sitemaps:edit. Every change saves right away.
 *
 * <p>400 lists what is wrong with a shape. 409 when the park's map was never saved,
 * or a surroundings pull is running. 404 when the property or the feature is not there.
 */
@RestController
@RequestMapping("/api/sitemaps/{propertyId}/features")
public class FeatureEditController {

    private final FeatureEditService featureEditService;
    private final PropertyService propertyService;

    public FeatureEditController(FeatureEditService featureEditService, PropertyService propertyService) {
        this.featureEditService = featureEditService;
        this.propertyService = propertyService;
    }

    @PreAuthorize("hasAuthority('sitemaps:edit')")
    @GetMapping
    public ResponseEntity<FeaturesResponse> features(@PathVariable UUID propertyId) {
        if (propertyService.findByPropertyId(propertyId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(featureEditService.features(propertyId));
    }

    // A new hand-made building or light
    @PreAuthorize("hasAuthority('sitemaps:edit')")
    @PostMapping
    public ResponseEntity<CreatedResponse> create(@PathVariable UUID propertyId, @RequestBody CreateRequest request) {
        if (propertyService.findByPropertyId(propertyId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreatedResponse(featureEditService.create(propertyId, request)));
    }

    @PreAuthorize("hasAuthority('sitemaps:edit')")
    @PostMapping("/{featureId}/rename")
    public ResponseEntity<RenamedResponse> rename(@PathVariable UUID propertyId, @PathVariable UUID featureId,
                                                  @RequestBody RenameRequest request) {
        return featureEditService.rename(propertyId, featureId, request)
                .map(n -> ResponseEntity.ok(new RenamedResponse(n)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('sitemaps:edit')")
    @PostMapping("/{featureId}/shape")
    public ResponseEntity<ShapedResponse> reshape(@PathVariable UUID propertyId, @PathVariable UUID featureId,
                                                  @RequestBody ShapeRequest request) {
        return featureEditService.reshape(propertyId, featureId, request)
                .map(id -> ResponseEntity.ok(new ShapedResponse(id)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAuthority('sitemaps:edit')")
    @DeleteMapping("/{featureId}")
    public ResponseEntity<DeletedResponse> delete(@PathVariable UUID propertyId, @PathVariable UUID featureId) {
        return featureEditService.delete(propertyId, featureId)
                .map(outcome -> ResponseEntity.ok(new DeletedResponse(outcome)))
                .orElse(ResponseEntity.notFound().build());
    }

    // Everything hidden comes back at the next pull
    @PreAuthorize("hasAuthority('sitemaps:edit')")
    @PostMapping("/unhide")
    public ResponseEntity<UnhiddenResponse> unhide(@PathVariable UUID propertyId) {
        if (propertyService.findByPropertyId(propertyId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new UnhiddenResponse(featureEditService.unhideAll(propertyId)));
    }
}

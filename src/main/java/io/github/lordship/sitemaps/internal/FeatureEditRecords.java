package io.github.lordship.sitemaps.internal;

import com.fasterxml.jackson.annotation.JsonRawValue;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * What Features mode in the map builder sends and gets back.
 * Geometry is GeoJSON in [longitude, latitude] order.
 */
public final class FeatureEditRecords {

    private FeatureEditRecords() { }

    // ---- GET /api/sitemaps/{propertyId}/features -----------------------------------

    /**
     * Everything Features mode can change, and how many pulled features are hidden.
     */
    public record FeaturesResponse(List<EditableFeature> features, int hidden) { }

    /**
     * One building, light or road.
     *
     * @param pulled       true when it came from OpenStreetMap. Changes to it are kept apart,
     *                     so they survive the next pull
     * @param widthMetres  a road's width; 0 for the others
     * @param buildingType Home, Office, Clubhouse...; null when not given
     */
    public record EditableFeature(
            UUID id,
            String kind,
            String name,
            boolean pulled,
            @JsonRawValue String geometry,
            double widthMetres,
            String buildingType
    ) { }

    // ---- POST /api/sitemaps/{propertyId}/features ------------------------------------

    /** A new hand-made building or light. buildingType is for buildings only. */
    public record CreateRequest(String kind, String name, JsonNode geometry, String buildingType) { }

    public record CreatedResponse(UUID id) { }

    // ---- POST .../features/{featureId}/rename ---------------------------------------

    /**
     * A new name. Blank clears it.
     *
     * @param allPieces for a road: also rename every other road in the park with the same
     *                  current name. OpenStreetMap often splits one street into several pieces
     */
    public record RenameRequest(String name, boolean allPieces) { }

    public record RenamedResponse(int renamed) { }

    // ---- POST .../features/{featureId}/shape -----------------------------------------

    /**
     * A change to a feature's shape, a light's spot, a road's width or a building's type.
     * A null part is left as it is. name: null keeps it, blank clears it.
     */
    public record ShapeRequest(JsonNode geometry, String name, String buildingType, Double widthMetres) { }

    /** The feature that holds the change now. A pulled feature is replaced by a hand-made copy. */
    public record ShapedResponse(UUID id) { }

    // ---- DELETE .../features/{featureId} --------------------------------------------

    /**
     * deleted:  a hand-made feature is gone
     * hidden:   a pulled feature is left out, now and after every pull
     * reverted: a hand-corrected copy is gone, and the original comes back at the next pull
     */
    public record DeletedResponse(String outcome) { }

    // ---- POST .../features/unhide -------------------------------------------------------

    /** How many pulled features come back at the next pull. */
    public record UnhiddenResponse(int broughtBack) { }
}

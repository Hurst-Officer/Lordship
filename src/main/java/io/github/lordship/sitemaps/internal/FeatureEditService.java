package io.github.lordship.sitemaps.internal;

import io.github.lordship.audit.AuditService;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.sitemaps.internal.FeatureEditRecords.CreateRequest;
import io.github.lordship.sitemaps.internal.FeatureEditRecords.EditableFeature;
import io.github.lordship.sitemaps.internal.FeatureEditRecords.FeaturesResponse;
import io.github.lordship.sitemaps.internal.FeatureEditRecords.RenameRequest;
import io.github.lordship.sitemaps.internal.FeatureEditRecords.ShapeRequest;
import io.github.lordship.sitemaps.internal.FeatureEditRepository.FeatureRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Features mode in the map builder: buildings, lights and roads the office adds or changes by hand.
 *
 * <p>The office worker would follow these steps:
 * <ol>
 *   <li>draw a building by clicking its corners, and give it a name and a type</li>
 *   <li>drop a light with one click</li>
 *   <li>rename a road or building. A road rename can cover every piece with the same name</li>
 *   <li>reshape a building, move a light, or move a road's bends and change its width</li>
 *   <li>delete. A hand-made feature is deleted. A pulled one is hidden, and stays hidden after
 *       every pull. Deleting a hand-corrected copy brings the original back at the next pull</li>
 * </ol>
 * Every change saves right away. Nothing waits for the map's own Save.
 *
 * <p>A pulled feature is rewritten by every pull, so a change to it is kept apart:
 * a rename or hide goes in map_feature_edit; a reshape makes a hand-made copy and hides the original.
 * Changes and a pull's save share a lock, and changes are refused while a pull is fetching,
 * so a change can never land halfway through a pull.
 */
@Service
public class FeatureEditService {

    static final List<String> BUILDING_TYPES = List.of("Home", "Office", "Clubhouse", "Laundry", "Well house",
            "Pump house", "Storage", "Shop", "Garage", "Other");

    // Kinds that can be drawn from scratch
    private static final Set<String> NEW_KINDS = Set.of("building", "light");
    // The widest road the width box accepts, in meters (about 200 ft)
    private static final double MAX_ROAD_WIDTH_M = 60;
    private static final double MIN_ROAD_WIDTH_M = 0.5;
    private static final int NAME_MAX = 80;

    private final FeatureEditRepository repository;
    private final SurroundingsRepository surroundingsRepository;
    private final SurroundingsService surroundingsService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public FeatureEditService(FeatureEditRepository repository,
                              SurroundingsRepository surroundingsRepository,
                              SurroundingsService surroundingsService,
                              AuditService auditService,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.surroundingsRepository = surroundingsRepository;
        this.surroundingsService = surroundingsService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    // ---- reading -------------------------------------------------------------------

    @Transactional(readOnly = true)
    public FeaturesResponse features(UUID propertyId) {
        List<EditableFeature> features = repository.findEditable(propertyId).stream()
                .map(row -> {
                    JsonNode details = objectMapper.readTree(row.details());
                    return new EditableFeature(row.uuid(), row.kind(), row.name(), !row.source().equals("manual"),
                            row.geometry(), details.path("width_m").asDouble(0),
                            details.hasNonNull("building_type") ? details.get("building_type").asString() : null);
                })
                .toList();
        return new FeaturesResponse(features, repository.countHidden(propertyId));
    }

    // ---- changing ------------------------------------------------------------------

    /** Saves a new hand-made building or light. Returns its id. */
    @Transactional
    public UUID create(UUID propertyId, CreateRequest request) {
        String kind = request.kind() == null ? "" : request.kind();
        if (!NEW_KINDS.contains(kind)) {
            throw InvalidRequest.onField("kind", "feature.kind_not_new", kind);
        }
        String geometry = checkedShape(kind, request.geometry());
        String buildingType = checkedType(kind, request.buildingType());
        startEdit(propertyId);

        Map<String, Object> details = new LinkedHashMap<>();
        if (buildingType != null) {
            details.put("building_type", buildingType);
        }
        String name = cleanName(request.name());
        UUID id = repository.insertManual(propertyId, kind, name, geometry, "manual", json(details));
        if (kind.equals("building")) {
            surroundingsRepository.linkBuildings(propertyId);
        }
        auditService.recordInsert("map_feature", id, auditView(kind, name, "manual", details));
        return id;
    }

    /**
     * Renames a feature, and for a road with allPieces, every other road in the park with the same
     * current name. Returns how many were renamed, or empty when the feature is gone.
     */
    @Transactional
    public Optional<Integer> rename(UUID propertyId, UUID featureId, RenameRequest request) {
        startEdit(propertyId);
        Optional<FeatureRow> found = editable(propertyId, featureId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        FeatureRow row = found.get();
        String name = cleanName(request.name());

        List<FeatureRow> targets = new ArrayList<>(List.of(row));
        if (request.allPieces() && row.kind().equals("road") && row.name() != null) {
            targets.addAll(repository.findSameName(propertyId, row.kind(), row.name(), row.uuid()));
        }
        for (FeatureRow target : targets) {
            repository.rename(target.uuid(), name);
            if (!target.source().equals("manual")) {
                // '' means "no name", and still overrides the pulled name
                repository.recordEdit(propertyId, target.sourceRef(), name == null ? "" : name, null);
            }
            auditService.recordUpdate("map_feature", target.uuid(), mapOf("name", target.name()), mapOf("name", name));
        }
        return Optional.of(targets.size());
    }

    /**
     * Changes a feature's shape, a light's spot, a road's width, a building's type or its name, in one go.
     * A pulled feature is replaced by a hand-made copy, and the original is hidden.
     * Returns the id of the feature that holds the change now, or empty when the feature is gone.
     */
    @Transactional
    public Optional<UUID> reshape(UUID propertyId, UUID featureId, ShapeRequest request) {
        startEdit(propertyId);
        Optional<FeatureRow> found = editable(propertyId, featureId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        FeatureRow row = found.get();
        String kind = row.kind();

        String geometry = request.geometry() == null || request.geometry().isNull()
                ? row.geometry()
                : checkedShape(kind, request.geometry());
        ObjectNode details = (ObjectNode) objectMapper.readTree(row.details());
        if (request.buildingType() != null && kind.equals("building")) {
            String type = checkedType(kind, request.buildingType());
            if (type == null) {
                details.remove("building_type");
            } else {
                details.put("building_type", type);
            }
        }
        if (request.widthMetres() != null && kind.equals("road")) {
            double width = request.widthMetres();
            if (!Double.isFinite(width) || width < MIN_ROAD_WIDTH_M || width > MAX_ROAD_WIDTH_M) {
                throw InvalidRequest.onField("widthMetres", "feature.width_out_of_range");
            }
            details.put("width_m", Math.round(width * 100) / 100.0);
        }
        String name = request.name() == null ? row.name() : cleanName(request.name());

        Map<String, Object> before = auditView(kind, row.name(), row.source(), detailsMap(row.details()));
        UUID holder;
        if (row.source().equals("manual")) {
            repository.updateManual(row.uuid(), name, geometry, details.toString());
            holder = row.uuid();
            auditService.recordUpdate("map_feature", holder, before, auditView(kind, name, "manual", detailsMap(details.toString())));
        } else {
            // A pulled feature: a hand-made copy keeps its name and details, and the original is
            // hidden so the next pull leaves it out
            details.put("replaces", row.sourceRef());
            holder = repository.insertManual(propertyId, kind, name, geometry, "replaces:" + row.sourceRef(),
                    details.toString());
            repository.recordEdit(propertyId, row.sourceRef(), null, true);
            repository.delete(row.uuid());
            auditService.recordDelete("map_feature", row.uuid(), before);
            auditService.recordInsert("map_feature", holder, auditView(kind, name, "manual", detailsMap(details.toString())));
        }
        if (kind.equals("building")) {
            surroundingsRepository.linkBuildings(propertyId);
        }
        return Optional.of(holder);
    }

    /**
     * Deletes a hand-made feature, or hides a pulled one so pulls leave it out.
     * Deleting a hand-corrected copy brings the original back at the next pull.
     * Returns deleted, hidden or reverted, or empty when the feature is gone.
     */
    @Transactional
    public Optional<String> delete(UUID propertyId, UUID featureId) {
        startEdit(propertyId);
        Optional<FeatureRow> found = editable(propertyId, featureId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        FeatureRow row = found.get();
        String ref = row.sourceRef() == null ? "" : row.sourceRef();
        String outcome;
        if (!row.source().equals("manual")) {
            repository.recordEdit(propertyId, ref, null, true);
            outcome = "hidden";
        } else if (ref.startsWith("replaces:")) {
            // The copy's name goes with it, so the original comes back named
            repository.bringBack(propertyId, ref.substring("replaces:".length()), row.name() == null ? "" : row.name());
            outcome = "reverted";
        } else {
            outcome = "deleted";
        }
        repository.delete(row.uuid());
        auditService.recordDelete("map_feature", row.uuid(), auditView(row.kind(), row.name(), row.source(),
                detailsMap(row.details())));
        if (row.kind().equals("building")) {
            surroundingsRepository.linkBuildings(propertyId);
        }
        return Optional.of(outcome);
    }

    /** Everything hidden comes back at the next pull. Returns how many. */
    @Transactional
    public int unhideAll(UUID propertyId) {
        startEdit(propertyId);
        int count = repository.unhideAll(propertyId);
        if (count > 0) {
            auditService.recordUpdate("site_map", propertyId, Map.of("hiddenFeatures", count),
                    Map.of("hiddenFeatures", 0));
        }
        return count;
    }

    // ---- rules -----------------------------------------------------------------------

    // Changes need a saved map, and no pull fetching right now: a pull rewrites pulled rows,
    // so a change made halfway through one could be lost. Then the park's lock is taken.
    private void startEdit(UUID propertyId) {
        if (!repository.isPlaced(propertyId)) {
            throw RuleConflict.of("feature.park_not_placed");
        }
        if (surroundingsService.isRunning(propertyId)) {
            throw RuleConflict.of("feature.pull_running");
        }
        surroundingsRepository.lockPark(propertyId);
    }

    // The feature, read again inside the lock. Only buildings, lights and roads can be changed:
    // contours share one source id, so hiding one would hide them all.
    private Optional<FeatureRow> editable(UUID propertyId, UUID featureId) {
        Optional<FeatureRow> row = repository.find(propertyId, featureId);
        if (row.isPresent() && !FeatureEditRepository.EDITABLE.contains(row.get().kind())) {
            throw RuleConflict.of("feature.kind_not_editable", row.get().kind());
        }
        return row;
    }

    /**
     * The shape as GeoJSON text, checked for its kind:
     * a building is a Polygon with at least 3 corners whose edges do not cross,
     * a light is a Point, a road is a LineString or MultiLineString with at least 2 points in each line.
     */
    String checkedShape(String kind, JsonNode geometry) {
        String type = geometry == null ? "" : geometry.path("type").asString("");
        JsonNode coordinates = geometry == null ? null : geometry.get("coordinates");
        boolean shapeFits = switch (kind) {
            case "building" -> type.equals("Polygon");
            case "light" -> type.equals("Point");
            case "road" -> type.equals("LineString") || type.equals("MultiLineString");
            default -> false;
        };
        if (!shapeFits || coordinates == null || !coordinates.isArray()) {
            throw InvalidRequest.onField("geometry", "feature.shape_wrong", kind);
        }

        switch (kind) {
            case "building" -> {
                ArrayNode ring = coordinates.path(0).isArray() ? (ArrayNode) coordinates.get(0) : null;
                if (ring == null || !allPoints(ring)) {
                    throw InvalidRequest.onField("geometry", "feature.corner_invalid");
                }
                // A ring repeats its first corner at the end
                if (!ring.get(0).equals(ring.get(ring.size() - 1))) {
                    ring.add(ring.get(0).deepCopy());
                }
                if (ring.size() < 4) {
                    throw InvalidRequest.onField("geometry", "feature.building_too_few_corners");
                }
                ArrayNode onlyOuter = objectMapper.createArrayNode();
                onlyOuter.add(ring);
                String shape = objectMapper.createObjectNode().put("type", "Polygon").set("coordinates", onlyOuter).toString();
                if (!repository.isValidShape(shape)) {
                    throw InvalidRequest.onField("geometry", "feature.outline_crosses_itself");
                }
                return shape;
            }
            case "light" -> {
                if (!isPoint(coordinates)) {
                    throw InvalidRequest.onField("geometry", "feature.corner_invalid");
                }
                return objectMapper.createObjectNode().put("type", "Point").set("coordinates", coordinates).toString();
            }
            default -> {
                List<JsonNode> lines = new ArrayList<>();
                if (type.equals("LineString")) {
                    lines.add(coordinates);
                } else {
                    coordinates.forEach(lines::add);
                }
                if (lines.isEmpty()) {
                    throw InvalidRequest.onField("geometry", "feature.road_too_few_points");
                }
                for (JsonNode line : lines) {
                    if (!line.isArray() || !allPoints(line)) {
                        throw InvalidRequest.onField("geometry", "feature.corner_invalid");
                    }
                    if (line.size() < 2) {
                        throw InvalidRequest.onField("geometry", "feature.road_too_few_points");
                    }
                }
                return objectMapper.createObjectNode().put("type", type).set("coordinates", coordinates).toString();
            }
        }
    }

    // null clears the type. An unknown type is refused.
    private static String checkedType(String kind, String buildingType) {
        if (!kind.equals("building") || buildingType == null || buildingType.isBlank()) {
            return null;
        }
        String type = buildingType.strip();
        if (!BUILDING_TYPES.contains(type)) {
            throw InvalidRequest.onField("buildingType", "feature.building_type_unknown", type);
        }
        return type;
    }

    // A typed name, tidied: no control characters, single spaces, at most 80 characters. Null when nothing is left.
    static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String clean = name.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        if (clean.length() > NAME_MAX) {
            clean = clean.substring(0, NAME_MAX).strip();
        }
        return clean.isEmpty() ? null : clean;
    }

    private static boolean allPoints(JsonNode list) {
        if (list.isEmpty()) {
            return false;
        }
        for (JsonNode p : list) {
            if (!isPoint(p)) {
                return false;
            }
        }
        return true;
    }

    // [longitude, latitude], both real numbers on the globe
    private static boolean isPoint(JsonNode p) {
        if (!p.isArray() || p.size() < 2 || !p.get(0).isNumber() || !p.get(1).isNumber()) {
            return false;
        }
        double lng = p.get(0).asDouble();
        double lat = p.get(1).asDouble();
        return Double.isFinite(lng) && Double.isFinite(lat) && lng >= -180 && lng <= 180 && lat >= -90 && lat <= 90;
    }

    // ---- audit and json ----------------------------------------------------------------

    // What the audit log keeps of a feature. The shape is left out: it is long and says little in a log.
    private static Map<String, Object> auditView(String kind, String name, String source, Map<String, Object> details) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("name", name);
        out.put("source", source);
        if (details.get("building_type") != null) {
            out.put("building_type", details.get("building_type"));
        }
        if (details.get("width_m") != null) {
            out.put("width_m", details.get("width_m"));
        }
        if (details.get("replaces") != null) {
            out.put("replaces", details.get("replaces"));
        }
        return out;
    }

    private Map<String, Object> detailsMap(String json) {
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode node = objectMapper.readTree(json);
        for (String key : List.of("building_type", "width_m", "replaces")) {
            if (node.hasNonNull(key)) {
                out.put(key, node.get(key).isNumber() ? node.get(key).asDouble() : node.get(key).asString());
            }
        }
        return out;
    }

    private static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(key, value);
        return out;
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }
}

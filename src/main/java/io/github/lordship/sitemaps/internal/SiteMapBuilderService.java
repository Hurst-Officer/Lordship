package io.github.lordship.sitemaps.internal;

import io.github.lordship.audit.ActingAgent;
import io.github.lordship.audit.AuditContext;
import io.github.lordship.audit.AuditService;
import io.github.lordship.documentfiles.DocumentFile;
import io.github.lordship.documentfiles.DocumentFileService;
import io.github.lordship.documentfiles.StoredFile;
import io.github.lordship.lots.Lot;
import io.github.lordship.lots.LotService;
import io.github.lordship.lots.ShapeData;
import io.github.lordship.properties.Property;
import io.github.lordship.properties.PropertyService;
import io.github.lordship.shared.DomainProblem.Problem;
import io.github.lordship.shared.InvalidRequest;
import io.github.lordship.sitemaps.internal.SiteMapBuilderRecords.BuilderLot;
import io.github.lordship.sitemaps.internal.SiteMapBuilderRecords.BuilderResponse;
import io.github.lordship.sitemaps.internal.SiteMapBuilderRecords.CommitLot;
import io.github.lordship.sitemaps.internal.SiteMapBuilderRecords.CommitRequest;
import io.github.lordship.sitemaps.internal.SiteMapBuilderRecords.CommitResponse;
import io.github.lordship.sitemaps.internal.SiteMapBuilderRecords.Imagery;
import io.github.lordship.sitemaps.internal.SiteMapBuilderRecords.Placement;
import io.github.lordship.sitemaps.internal.SiteMapBuilderRepository.PlacementRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The map builder: opening a park, and saving the map the office worker made.
 *
 * <p>The office worker reads a plat drawing in Glade, checks the lot numbers,
 * lines the drawing up with the satellite view, then saves. Saving:
 * <ol>
 *   <li>adds any lot number the park does not have yet</li>
 *   <li>replaces every lot outline of the park, in one transaction</li>
 *   <li>keeps each lot's corners on the drawing, so the park can be reopened</li>
 *   <li>keeps the drawing itself and how it was pinned to the globe</li>
 *   <li>writes one audit entry for the whole map</li>
 *   <li>once the save is in the database, pulls the park's surroundings in the background:
 *       roads, water, buildings, property lines and ground heights</li>
 * </ol>
 */
@Service
public class SiteMapBuilderService {

    // A lot number starts and ends with a letter or digit. OCR misreads such as "12,", "13|" and "7." fail this.
    private static final Pattern LOT_NUMBER = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9 ./&#-]*[A-Za-z0-9])?$");
    private static final int LOT_NUMBER_MAX = 20;
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/bmp",
            "image/tiff", "image/webp");

    private final SiteMapBuilderRepository repository;
    private final PropertyService propertyService;
    private final LotService lotService;
    private final DocumentFileService documentFileService;
    private final AuditService auditService;
    private final AuditContext auditContext;
    private final SurroundingsService surroundingsService;
    private final ObjectMapper objectMapper;
    private final Imagery imagery;

    public SiteMapBuilderService(SiteMapBuilderRepository repository,
                                 PropertyService propertyService,
                                 LotService lotService,
                                 DocumentFileService documentFileService,
                                 AuditService auditService,
                                 AuditContext auditContext,
                                 SurroundingsService surroundingsService,
                                 ObjectMapper objectMapper,
                                 @Value("${lordship.imagery.tiles:}") String tiles,
                                 @Value("${lordship.imagery.key:}") String key,
                                 @Value("${lordship.imagery.max-zoom:19}") int maxZoom,
                                 @Value("${lordship.imagery.credit:}") String credit) {
        this.repository = repository;
        this.propertyService = propertyService;
        this.lotService = lotService;
        this.documentFileService = documentFileService;
        this.auditService = auditService;
        this.auditContext = auditContext;
        this.surroundingsService = surroundingsService;
        this.objectMapper = objectMapper;
        String withKey = (key == null || key.isBlank()) ? tiles
                : tiles + (tiles.contains("?") ? "&" : "?") + "token=" + key.trim();
        this.imagery = new Imagery(withKey, maxZoom, credit);
    }

    /** A drawing uploaded with a save. */
    public record PlatUpload(String fileName, String contentType, byte[] content) { }

    // ---- opening -------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<BuilderResponse> open(UUID propertyId) {
        return propertyService.findByPropertyId(propertyId).map(property -> {
            Placement placement = repository.findPlacement(propertyId)
                    .filter(p -> p.platWidth() != null && p.platHeight() != null && p.imageToWorld() != null)
                    .map(p -> new Placement(p.platWidth(), p.platHeight(), p.platImageFileName(), p.imageToWorld(),
                            p.platFileId() != null, p.placedAt()))
                    .orElse(null);

            // A lot's corners on the drawing count only while it is on the map.
            // Every new lot starts with a placeholder shape, which is not a drawing.
            List<BuilderLot> lots = repository.findLots(propertyId).stream()
                    .map(row -> row.outline() == null
                            ? new BuilderLot(row.lotId(), row.lotNumber(), null, null)
                            : new BuilderLot(row.lotId(), row.lotNumber(), vertices(row.shapeData()), outline(row.outline())))
                    .toList();

            return new BuilderResponse(property.uuid(), property.propertyCode(), property.propertyName(),
                    address(property), property.latitude(), property.longitude(), imagery, placement, lots);
        });
    }

    /** The drawing saved with the last map, or empty when none was kept. */
    @Transactional(readOnly = true)
    public Optional<StoredFile> plat(UUID propertyId) {
        return repository.findPlacement(propertyId)
                .map(PlacementRow::platFileId)
                .flatMap(documentFileService::read);
    }

    // ---- saving --------------------------------------------------------------

    /**
     * Saves a finished map. Empty when there is no such property.
     *
     * @param upload the drawing, or null to keep the one saved last time
     */
    @Transactional
    public Optional<CommitResponse> commit(UUID propertyId, CommitRequest request, PlatUpload upload) {
        Optional<Property> found = propertyService.findByPropertyId(propertyId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        repository.lockProperty(propertyId);

        List<Problem> problems = problemsWith(request, upload);
        if (!problems.isEmpty()) {
            throw InvalidRequest.withDetails("sitemap.not_ready", problems);
        }

        Optional<PlacementRow> before = repository.findPlacement(propertyId);
        int placedBefore = repository.countOutlines(propertyId);

        // The park's live lots, by number written loosely: "Club House" and "CLUBHOUSE" are one lot
        String propertyCode = found.get().propertyCode();
        Map<String, Lot> byNumber = new LinkedHashMap<>();
        for (Lot lot : lotService.findByProperty(propertyCode)) {
            byNumber.putIfAbsent(loose(lot.lotNumber()), lot);
        }

        List<String> created = new ArrayList<>();
        repository.deleteOutlines(propertyId);
        for (CommitLot drawn : request.lots()) {
            String number = drawn.lotNumber().strip();
            Lot lot = byNumber.get(loose(number));
            if (lot == null) {
                lot = lotService.createLot(propertyId, number);
                byNumber.put(loose(number), lot);
                created.add(number);
            }
            repository.insertOutline(lot.uuid(), propertyId, ringGeoJson(drawn.outline()));
            lotService.placeShape(lot.uuid(), shapeData(drawn.vertices()));
        }

        UUID fileId = before.map(PlacementRow::platFileId).orElse(null);
        String fileName = before.map(PlacementRow::platImageFileName).orElse(null);
        if (upload != null) {
            DocumentFile saved = documentFileService.store(
                    "plats/" + propertyId + "/" + UUID.randomUUID() + extension(upload),
                    upload.fileName(), upload.contentType(), upload.content(),
                    ActingAgent.resolve(auditContext));
            fileId = saved.uuid();
            fileName = upload.fileName();
        }
        repository.savePlacement(propertyId, request.platWidth(), request.platHeight(), fileName, fileId,
                objectMapper.writeValueAsString(request.imageToWorld()), request.imagery(),
                ActingAgent.resolve(auditContext));

        Set<String> onMap = request.lots().stream().map(l -> loose(l.lotNumber())).collect(Collectors.toSet());
        List<String> unplaced = lotService.findByProperty(propertyCode).stream()
                .map(Lot::lotNumber)
                .filter(n -> !onMap.contains(loose(n)))
                .toList();

        audit(propertyId, before, placedBefore, request.lots().size(), created, fileName);

        // The lots moved, so the buildings are tied to lots again now. The surroundings are
        // pulled again once this save is in the database, around where the lots are now.
        surroundingsService.relinkBuildings(propertyId);
        surroundingsService.startAfterCommit(propertyId);

        return Optional.of(new CommitResponse(request.lots().size(), created, unplaced,
                repository.totalSquareFeet(propertyId)));
    }

    // One entry for the whole map: how many lots it holds, which were added, and which drawing.
    private void audit(UUID propertyId, Optional<PlacementRow> before, int placedBefore, int placedAfter,
                       List<String> created, String fileName) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("lotsOnMap", placedAfter);
        if (!created.isEmpty()) {
            after.put("lotsAdded", created);
        }
        after.put("drawing", fileName);

        if (before.isEmpty()) {
            auditService.recordInsert("site_map", propertyId, after);
            return;
        }
        Map<String, Object> was = new LinkedHashMap<>();
        was.put("lotsOnMap", placedBefore);
        was.put("drawing", before.get().platImageFileName());
        was.put("placedAt", before.get().placedAt());
        auditService.recordUpdate("site_map", propertyId, was, after);
    }

    // Everything wrong with a map, so the office worker can fix it all in one pass
    private List<Problem> problemsWith(CommitRequest request, PlatUpload upload) {
        List<Problem> problems = new ArrayList<>();

        if (request.platWidth() == null || request.platWidth() <= 0
                || request.platHeight() == null || request.platHeight() <= 0) {
            problems.add(Problem.of("sitemap.plat_size_invalid"));
        }
        if (request.imageToWorld() == null || request.imageToWorld().isEmpty()) {
            problems.add(Problem.of("sitemap.not_placed"));
        }
        if (upload != null && (upload.contentType() == null
                || !IMAGE_TYPES.contains(upload.contentType().toLowerCase(Locale.ROOT)))) {
            problems.add(Problem.onField("plat", "sitemap.plat_not_an_image"));
        }
        if (request.lots().isEmpty()) {
            problems.add(Problem.of("sitemap.no_lots"));
        }

        Map<String, List<String>> spellings = new LinkedHashMap<>();
        for (CommitLot lot : request.lots()) {
            String number = lot.lotNumber() == null ? "" : lot.lotNumber().strip();
            if (number.isEmpty()) {
                problems.add(Problem.of("sitemap.lot_number_blank"));
                continue;
            }
            if (number.length() > LOT_NUMBER_MAX) {
                problems.add(Problem.of("sitemap.lot_number_too_long", number));
            } else if (!LOT_NUMBER.matcher(number).matches()) {
                problems.add(Problem.of("sitemap.lot_number_invalid", number));
            }
            spellings.computeIfAbsent(loose(number), k -> new ArrayList<>()).add(number);

            if (lot.vertices() == null || lot.vertices().size() < 3 || !allPairs(lot.vertices())
                    || lot.outline() == null || lot.outline().size() < 3) {
                problems.add(Problem.of("sitemap.lot_too_few_corners", number));
            } else if (!allOnEarth(lot.outline())) {
                problems.add(Problem.of("sitemap.lot_corner_invalid", number));
            } else if (!repository.isValidOutline(ringGeoJson(lot.outline()))) {
                problems.add(Problem.of("sitemap.lot_outline_crosses_itself", number));
            }
        }
        spellings.values().stream()
                .filter(names -> names.size() > 1)
                .forEach(names -> problems.add(Problem.of("sitemap.lot_numbers_repeat", String.join(", ", names))));

        return problems;
    }

    // ---- small helpers -----------------------------------------------------------

    // A lot number with case, spaces and punctuation ignored
    static String loose(String number) {
        return number == null ? "" : number.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static boolean allPairs(List<List<Double>> points) {
        return points.stream().allMatch(p -> p != null && p.size() == 2 && p.get(0) != null && p.get(1) != null
                && Double.isFinite(p.get(0)) && Double.isFinite(p.get(1)));
    }

    private static boolean allOnEarth(List<List<Double>> points) {
        return allPairs(points) && points.stream().allMatch(p ->
                p.get(0) >= -180 && p.get(0) <= 180 && p.get(1) >= -90 && p.get(1) <= 90);
    }

    // A closed GeoJSON polygon from corners that are not closed
    private String ringGeoJson(List<List<Double>> corners) {
        List<List<Double>> ring = new ArrayList<>(corners);
        if (!ring.getFirst().equals(ring.getLast())) {
            ring.add(ring.getFirst());
        }
        return objectMapper.writeValueAsString(Map.of("type", "Polygon", "coordinates", List.of(ring)));
    }

    private static ShapeData shapeData(List<List<Double>> vertices) {
        List<ShapeData.Point> points = vertices.stream()
                .map(v -> new ShapeData.Point(v.get(0), v.get(1)))
                .toList();
        double minX = points.stream().mapToDouble(ShapeData.Point::x).min().orElse(0);
        double minY = points.stream().mapToDouble(ShapeData.Point::y).min().orElse(0);
        double maxX = points.stream().mapToDouble(ShapeData.Point::x).max().orElse(0);
        double maxY = points.stream().mapToDouble(ShapeData.Point::y).max().orElse(0);
        double cx = points.stream().mapToDouble(ShapeData.Point::x).average().orElse(0);
        double cy = points.stream().mapToDouble(ShapeData.Point::y).average().orElse(0);
        return new ShapeData(points, new ShapeData.BoundingBox(minX, minY, maxX, maxY), new ShapeData.Point(cx, cy));
    }

    // The corners on the drawing, from shape_data. The placeholder every new lot gets is not a drawing.
    private List<List<Double>> vertices(String shapeDataJson) {
        if (shapeDataJson == null) {
            return null;
        }
        JsonNode list = objectMapper.readTree(shapeDataJson).path("vertices");
        if (!list.isArray() || list.size() < 3) {
            return null;
        }
        List<List<Double>> out = new ArrayList<>();
        for (JsonNode p : list) {
            out.add(List.of(p.get(0).asDouble(), p.get(1).asDouble()));
        }
        return out;
    }

    // The outline's corners without the closing repeat, or null
    private List<List<Double>> outline(String geoJson) {
        if (geoJson == null) {
            return null;
        }
        JsonNode ring = objectMapper.readTree(geoJson).path("coordinates").path(0);
        List<List<Double>> out = new ArrayList<>();
        for (JsonNode p : ring) {
            out.add(List.of(p.get(0).asDouble(), p.get(1).asDouble()));
        }
        if (out.size() > 1 && out.getFirst().equals(out.getLast())) {
            out.removeLast();
        }
        return out;
    }

    private static String address(Property p) {
        return String.join(", ", java.util.stream.Stream.of(p.propertyStreet(), p.propertyCity(),
                        (p.propertyState() == null ? "" : p.propertyState()) + " " + (p.propertyZip() == null ? "" : p.propertyZip()))
                .map(s -> s == null ? "" : s.strip())
                .filter(s -> !s.isEmpty())
                .toList());
    }

    private static String extension(PlatUpload upload) {
        String name = upload.fileName() == null ? "" : upload.fileName();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
        return ext.matches("\\.[a-z0-9]{1,5}") ? ext : "";
    }
}

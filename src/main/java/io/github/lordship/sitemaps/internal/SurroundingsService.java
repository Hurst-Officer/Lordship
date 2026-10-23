package io.github.lordship.sitemaps.internal;

import io.github.lordship.shared.DomainProblem.Problem;
import io.github.lordship.shared.RuleConflict;
import io.github.lordship.sitemaps.internal.SurroundingsRepository.PullArea;
import io.github.lordship.sitemaps.internal.SurroundingsSources.SourceDown;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pulls everything around a park that a map needs besides the lots: roads, water, shoreline,
 * streams, buildings, county property lines and the height of the ground.
 *
 * <p>The office worker does not start this by hand most of the time:
 * <ol>
 *   <li>Saving a map in the builder starts a pull once the save is in the database.</li>
 *   <li>The builder shows "pulling" and then what was found.</li>
 *   <li>"Refresh surroundings" pulls again, for when a road or parcel changed.</li>
 * </ol>
 *
 * <p>A pull works in two parts:
 * <ol>
 *   <li>fetch: ask every source, outside any transaction. This can take a minute.</li>
 *   <li>save: in one transaction, replace the rows from each source that answered.
 *       A source that could not be reached keeps what was saved from it last time,
 *       so a network hiccup never wipes a good set of roads.</li>
 * </ol>
 * Pulls run in the background, at most two at a time. Asking for a pull while one runs for
 * the same park queues one more, because the lots, and so the area, may have moved since.
 */
@Service
public class SurroundingsService {

    private static final Logger log = LoggerFactory.getLogger(SurroundingsService.class);

    private static final String OSM_CREDIT = "© OpenStreetMap contributors";
    private static final String PARCELS_CREDIT = "Parcels: Washington State Geospatial Open Data Portal";
    private static final String HEIGHTS_CREDIT = "Elevation: USGS 3DEP";

    /**
     * What the last pull of a park is doing, or did.
     *
     * @param state  never, running, done or failed
     * @param counts rows of each kind saved, plus "home": lots with a home on them
     * @param notes  sources that could not be reached, in words
     * @param error  why a failed pull failed, in words
     */
    public record Status(String state, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                         Map<String, Integer> counts, List<String> notes, String error) { }

    /** What a pull fetched. A null part means that source could not be used this time. */
    record Fetched(JsonNode osm, JsonNode parcels, HeightTiff.Grid heights, Elevation.GridRequest heightsAsked,
                   List<Problem> problems) { }

    // A pull's result, kept in memory for the screens to read
    record Outcome(Map<String, Integer> counts, List<Problem> problems) { }

    // One park's pull, while it runs and after
    private static final class Run {
        volatile String state = "running";
        volatile boolean again;
        final OffsetDateTime startedAt = OffsetDateTime.now();
        volatile OffsetDateTime finishedAt;
        volatile Outcome outcome;
        volatile Problem error;
    }

    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "surroundings-pull");
        thread.setDaemon(true);
        return thread;
    });

    private final SurroundingsRepository repository;
    private final SurroundingsSources sources;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;
    private final MessageSource messages;
    private final int marginMeters;

    public SurroundingsService(SurroundingsRepository repository,
                               SurroundingsSources sources,
                               PlatformTransactionManager transactionManager,
                               ObjectMapper objectMapper,
                               MessageSource messages,
                               @Value("${lordship.surroundings.margin-meters:150}") int marginMeters) {
        this.repository = repository;
        this.sources = sources;
        this.transaction = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.messages = messages;
        this.marginMeters = marginMeters;
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }

    // ---- starting ------------------------------------------------------------------------

    /**
     * Starts a pull once the current transaction commits, so the pull sees the lots just saved.
     * A transaction that rolls back starts nothing. Outside a transaction it starts now.
     */
    public void startAfterCommit(UUID propertyId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            start(propertyId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                start(propertyId);
            }
        });
    }

    /**
     * Starts a pull in the background. Throws RuleConflict when the park has no lots on the map:
     * the area to pull is worked out from them.
     */
    public Status refresh(UUID propertyId) {
        if (repository.pullArea(propertyId, marginMeters).isEmpty()) {
            throw RuleConflict.of("surroundings.no_lots");
        }
        start(propertyId);
        return status(propertyId);
    }

    private void start(UUID propertyId) {
        Run fresh = new Run();
        Run current = runs.compute(propertyId, (id, run) -> {
            if (run != null) {
                // The same lock the run takes as it finishes, so a request cannot slip in between
                synchronized (run) {
                    if (run.state.equals("running")) {
                        // Pull once more when this one ends: the lots may have moved since it worked out its area
                        run.again = true;
                        return run;
                    }
                }
            }
            return fresh;
        });
        if (current == fresh) {
            pool.submit(() -> runUntilSettled(propertyId, fresh));
        }
    }

    private void runUntilSettled(UUID propertyId, Run run) {
        while (true) {
            Outcome outcome = null;
            Problem error = null;
            try {
                outcome = pull(propertyId);
            } catch (RuleConflict e) {
                error = e.problem();
            } catch (RuntimeException e) {
                log.warn("Pulling the surroundings of {} failed", propertyId, e);
                error = Problem.of("surroundings.failed", SurroundingsSources.firstLine(
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            synchronized (run) {
                if (run.again) {
                    run.again = false;
                    continue;   // asked again while running: go round once more, still "running"
                }
                run.outcome = outcome;
                run.error = error;
                run.finishedAt = OffsetDateTime.now();
                run.state = error == null ? "done" : "failed";
                return;
            }
        }
    }

    // ---- reading -------------------------------------------------------------------------

    /** What the last pull is doing or did. A pull from before a restart is read from the database. */
    public Status status(UUID propertyId) {
        Run run = runs.get(propertyId);
        if (run != null && (run.state.equals("running") || run.state.equals("failed"))) {
            return new Status(run.state, run.startedAt, run.finishedAt, Map.of(), List.of(),
                    run.error == null ? null : words(run.error));
        }
        if (run != null && run.outcome != null) {
            return new Status("done", run.startedAt, run.finishedAt, run.outcome.counts(),
                    run.outcome.problems().stream().map(this::words).toList(), null);
        }
        return repository.findLastPull(propertyId)
                .map(last -> {
                    JsonNode details = objectMapper.readTree(last.details());
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    details.path("counts").properties().forEach(e -> counts.put(e.getKey(), e.getValue().asInt()));
                    List<String> notes = new ArrayList<>();
                    for (JsonNode p : details.path("problems")) {
                        List<Object> args = new ArrayList<>();
                        p.path("args").forEach(a -> args.add(a.asString()));
                        notes.add(words(new Problem(p.path("code").asString(), null, args)));
                    }
                    return new Status("done", null, last.fetchedAt(), counts, notes, null);
                })
                .orElse(new Status("never", null, null, Map.of(), List.of(), null));
    }

    /** True while a pull for this park is fetching or saving. Hand edits wait for it. */
    public boolean isRunning(UUID propertyId) {
        Run run = runs.get(propertyId);
        return run != null && run.state.equals("running");
    }

    public Optional<byte[]> hillshade(UUID propertyId) {
        return repository.findHillshade(propertyId);
    }

    private String words(Problem problem) {
        Locale locale = LocaleContextHolder.getLocale();
        return messages.getMessage(problem.code(), problem.args().toArray(), problem.code(), locale);
    }

    // ---- the pull --------------------------------------------------------------------------

    private Outcome pull(UUID propertyId) {
        PullArea area = repository.pullArea(propertyId, marginMeters)
                .orElseThrow(() -> RuleConflict.of("surroundings.no_lots"));
        return save(propertyId, area, fetch(area));
    }

    // Asks every source. A source that fails is noted, and the others carry on.
    private Fetched fetch(PullArea area) {
        List<Problem> problems = new ArrayList<>();
        JsonNode osm = null;
        JsonNode parcels = null;
        HeightTiff.Grid heights = null;
        Elevation.GridRequest asked = Elevation.gridFor(
                new Elevation.Box(area.south(), area.west(), area.north(), area.east()));
        try {
            osm = sources.openStreetMap(area);
        } catch (SourceDown e) {
            problems.add(Problem.of("surroundings.osm_unreachable", e.getMessage()));
        }
        try {
            parcels = sources.parcels(area);
        } catch (SourceDown e) {
            problems.add(Problem.of("surroundings.parcels_unreachable", e.getMessage()));
        }
        try {
            heights = sources.heights(asked);
        } catch (SourceDown e) {
            problems.add(Problem.of("surroundings.heights_unreachable", e.getMessage()));
        }
        return new Fetched(osm, parcels, heights, asked, problems);
    }

    /**
     * Replaces the park's saved surroundings with what was fetched, in one transaction.
     * Package-private so tests can hand in what the sources would have said.
     */
    Outcome save(UUID propertyId, PullArea area, Fetched fetched) {
        List<Problem> problems = new ArrayList<>(fetched.problems());

        // Work out the heights before the transaction: it is the slow part
        Elevation.Built heights = null;
        if (fetched.heights() != null) {
            try {
                heights = Elevation.build(new Elevation.Box(area.south(), area.west(), area.north(), area.east()),
                        fetched.heightsAsked(), fetched.heights());
            } catch (IllegalArgumentException e) {
                problems.add(Problem.of("surroundings.heights_unreachable", e.getMessage()));
            }
        }
        OsmFeatures.Result osm = OsmFeatures.rows(fetched.osm());
        Elevation.Built built = heights;

        return transaction.execute(tx -> {
            repository.lockPark(propertyId);   // wait for any hand edit that is saving right now

            // Nothing at all where there was something before is a bad answer,
            // not a park whose roads vanished overnight
            boolean osmOk = fetched.osm() != null;
            if (osmOk && osm.rows().isEmpty() && repository.hasRowsFrom(propertyId, "osm")) {
                osmOk = false;
                problems.add(Problem.of("surroundings.osm_empty"));
            }
            boolean parcelsOk = fetched.parcels() != null;
            if (parcelsOk && fetched.parcels().isEmpty() && repository.hasRowsFrom(propertyId, "wa_parcels")) {
                parcelsOk = false;
                problems.add(Problem.of("surroundings.parcels_empty"));
            }
            boolean heightsOk = built != null;
            if (!osmOk && !parcelsOk && !heightsOk) {
                throw RuleConflict.of("surroundings.nothing_reached");
            }

            repository.deletePulled(propertyId, osmOk, parcelsOk, heightsOk);

            if (osmOk) {
                for (OsmFeatures.Row row : osm.rows()) {
                    repository.insertOsm(propertyId, row.kind(), row.name(), lines(row.lines()), row.buildArea(),
                            area.geoJson(), row.ref(), json(row.details()));
                }
                List<double[]> seaPoints = OsmFeatures.seaTestPoints(osm.coastlines(), area.cosLat());
                if (!seaPoints.isEmpty()) {
                    repository.insertSea(propertyId, area.geoJson(),
                            json(Map.of("type", "MultiPoint", "coordinates", seaPoints)),
                            json(Map.of("water", "sea", "made_from", "coastline")));
                }
            }

            if (parcelsOk) {
                for (JsonNode feature : fetched.parcels()) {
                    JsonNode geometry = feature.path("geometry");
                    if (!geometry.isObject()) {
                        continue;
                    }
                    JsonNode props = feature.path("properties");
                    repository.insertParcel(propertyId,
                            props.hasNonNull("SITUS_ADDRESS") ? props.get("SITUS_ADDRESS").asString() : null,
                            "parcel:" + props.path("PARCEL_ID_NR").asString(""),
                            props.isObject() ? props.toString() : "{}",
                            geometry.toString());
                }
                // The parcels the lots sit on become the park boundary
                repository.makeParkBoundary(propertyId);
            }

            if (heightsOk) {
                Elevation.Summary summary = built.summary();
                for (Elevation.Contour contour : built.contours()) {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("elevation_ft", contour.levelFt());
                    details.put("elevation_m", Math.round(contour.levelFt() / Elevation.FT_PER_M * 100) / 100.0);
                    details.put("index", contour.index());
                    details.put("interval_ft", summary.contourIntervalFt());
                    details.put("datum", "NAVD88");
                    repository.insertContour(propertyId,
                            json(Map.of("type", "LineString", "coordinates", contour.points())),
                            area.geoJson(), feet(contour.levelFt()), json(details));
                }
                repository.saveElevation(propertyId, summary, littleEndian(fetched.heights().values()),
                        built.hillshadePng());
            }

            // The office's renames and hides of pulled roads and buildings
            repository.applyEdits(propertyId);
            int homes = repository.linkBuildings(propertyId);

            Map<String, Integer> counts = repository.countsByKind(propertyId);
            counts.put("home", homes);

            List<String> credits = new ArrayList<>();
            if (osmOk || repository.hasRowsFrom(propertyId, "osm")) {
                credits.add(OSM_CREDIT);
            }
            if (counts.containsKey("parcel") || counts.containsKey("park_boundary")) {
                credits.add(PARCELS_CREDIT);
            }
            if (heightsOk || repository.hasElevation(propertyId)) {
                credits.add(HEIGHTS_CREDIT);
            }
            repository.saveCredits(propertyId, json(credits));

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("margin_m", marginMeters);
            record.put("counts", counts);
            record.put("problems", problems.stream()
                    .map(p -> Map.of("code", p.code(), "args", p.args().stream().map(String::valueOf).toList()))
                    .toList());
            record.put("attribution", credits);
            repository.insertPullRecord(propertyId, area.geoJson(), json(record));

            return new Outcome(counts, List.copyOf(problems));
        });
    }

    /** Ties buildings to lots again. The builder calls this when a map is saved. */
    public void relinkBuildings(UUID propertyId) {
        repository.linkBuildings(propertyId);
    }

    // ---- small helpers -------------------------------------------------------------------

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    // One line as a LineString, several as a MultiLineString
    private String lines(List<List<double[]>> lines) {
        return lines.size() == 1
                ? json(Map.of("type", "LineString", "coordinates", lines.getFirst()))
                : json(Map.of("type", "MultiLineString", "coordinates", lines));
    }

    // "125 ft", or "2.5 ft" when the level is not a whole number
    private static String feet(double levelFt) {
        return (levelFt == Math.rint(levelFt) ? String.valueOf((long) levelFt) : String.valueOf(levelFt)) + " ft";
    }

    // The grid as site_elevation keeps it: 32-bit little-endian floats, top row first
    private static byte[] littleEndian(float[] values) {
        ByteBuffer out = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            out.putFloat(v < -500 || v > 9000 ? Float.NaN : v);
        }
        return out.array();
    }
}

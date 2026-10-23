package io.github.lordship.sitemaps.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The reads and writes of a surroundings pull.
 *
 * <p>Shapes arrive as GeoJSON text. Every overlay wraps shapes in ST_MakeValid first:
 * one self-crossing lot or building must not fail a whole pull.
 * A pull deletes and rewrites only the rows it owns. Rows with source 'manual' are never touched.
 */
@Repository
public class SurroundingsRepository {

    /** The sources a pull owns. Their rows are deleted and rewritten on every pull. */
    static final List<String> OWNED_SOURCES = List.of("osm", "wa_parcels", "derived", "usgs_3dep");

    private final JdbcClient db;

    public SurroundingsRepository(JdbcClient db) {
        this.db = db;
    }

    /** The area to pull: the lots' bounding box grown by a margin. GeoJSON plus its edges in degrees. */
    public record PullArea(String geoJson, double south, double west, double north, double east) {

        double cosLat() {
            return Math.cos(Math.toRadians((south + north) / 2));
        }
    }

    /** The last pull's own row: what it found and what went wrong. */
    public record LastPull(String details, OffsetDateTime fetchedAt) { }

    /** How many rows of one kind. */
    public record KindCount(String kind, int n) { }

    // A pull's save and a hand edit of a feature both take this lock, so they never overlap.
    // It is held until the transaction ends.
    public void lockPark(UUID propertyId) {
        db.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext('map_feature:' || :property::text))) locked")
                .param("property", propertyId)
                .query(Integer.class)
                .single();
    }

    // Empty when the park has no live lots on the map
    public Optional<PullArea> pullArea(UUID propertyId, int marginMeters) {
        return db.sql("""
                        WITH lots AS (
                            SELECT postgis.ST_Collect(o.outline) AS g
                              FROM lot_outline o
                              JOIN lot l ON l.uuid = o.lot_id
                             WHERE o.property_id = :property
                               AND l.deleted_at IS NULL
                        ),
                        area AS (
                            SELECT postgis.ST_Envelope(
                                       postgis.ST_Buffer(g::postgis.geography, :margin)::postgis.geometry) AS a
                              FROM lots
                             WHERE g IS NOT NULL
                        )
                        SELECT postgis.ST_AsGeoJSON(a) AS geo_json,
                               postgis.ST_YMin(a) AS south,
                               postgis.ST_XMin(a) AS west,
                               postgis.ST_YMax(a) AS north,
                               postgis.ST_XMax(a) AS east
                          FROM area
                        """)
                .param("property", propertyId)
                .param("margin", marginMeters)
                .query(PullArea.class)
                .optional();
    }

    public boolean hasRowsFrom(UUID propertyId, String source) {
        return db.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM map_feature
                             WHERE property_id = :property AND source = :source AND deleted_at IS NULL
                        )
                        """)
                .param("property", propertyId)
                .param("source", source)
                .query(Boolean.class)
                .single();
    }

    /**
     * Clears what is about to be replaced. A source that could not be reached keeps its rows.
     * Derived rows go with the source they were made from: the sea with OpenStreetMap's
     * shoreline, the park boundary with the parcels. The last pull's own row always goes.
     */
    public void deletePulled(UUID propertyId, boolean osm, boolean parcels, boolean heights) {
        db.sql("""
                        DELETE FROM map_feature
                         WHERE property_id = :property
                           AND (   (source = 'derived' AND source_ref = 'pull')
                                OR (:osm     AND (source = 'osm' OR (source = 'derived' AND source_ref = 'coastline')))
                                OR (:parcels AND (source = 'wa_parcels' OR (source = 'derived' AND source_ref = 'parcels')))
                                OR (:heights AND source = 'usgs_3dep'))
                        """)
                .param("property", propertyId)
                .param("osm", osm)
                .param("parcels", parcels)
                .param("heights", heights)
                .update();
    }

    /**
     * One OpenStreetMap feature, cut to the pulled area.
     * buildArea: the lines outline an area (a pond, a building), which is filled in first.
     */
    public void insertOsm(UUID propertyId, String kind, String name, String geoJson, boolean buildArea,
                          String area, String ref, String detailsJson) {
        db.sql("""
                        WITH raw AS (
                            SELECT postgis.ST_SetSRID(postgis.ST_GeomFromGeoJSON(:shape), 4326) AS g
                        ),
                        shaped AS (
                            SELECT CASE WHEN :buildArea THEN postgis.ST_BuildArea(postgis.ST_UnaryUnion(g)) ELSE g END AS g
                              FROM raw
                        ),
                        clipped AS (
                            SELECT postgis.ST_CollectionExtract(
                                       postgis.ST_Intersection(postgis.ST_MakeValid(g),
                                                               postgis.ST_SetSRID(postgis.ST_GeomFromGeoJSON(:area), 4326)),
                                       CASE WHEN :buildArea THEN 3 ELSE 2 END) AS g
                              FROM shaped
                             WHERE g IS NOT NULL
                        )
                        INSERT INTO map_feature (property_id, kind, name, geom, source, source_ref, details, fetched_at)
                        SELECT :property, :kind, :name, g, 'osm', :ref, :details::jsonb, now()
                          FROM clipped
                         WHERE g IS NOT NULL AND NOT postgis.ST_IsEmpty(g)
                        """)
                .param("shape", geoJson)
                .param("buildArea", buildArea)
                .param("area", area)
                .param("property", propertyId)
                .param("kind", kind)
                .param("name", name)
                .param("ref", ref)
                .param("details", detailsJson)
                .update();
    }

    /**
     * The sea. Cuts the pulled area along the shoreline and keeps the pieces on the water side:
     * the ones holding the test points. Lakes are already areas; this is for the coast.
     */
    public void insertSea(UUID propertyId, String area, String testPointsGeoJson, String detailsJson) {
        db.sql("""
                        WITH area AS (
                            SELECT postgis.ST_SetSRID(postgis.ST_GeomFromGeoJSON(:area), 4326) AS a
                        ),
                        shore AS (
                            SELECT postgis.ST_Union(geom) AS s
                              FROM map_feature
                             WHERE property_id = :property AND kind = 'coastline' AND deleted_at IS NULL
                        ),
                        pieces AS (
                            SELECT (postgis.ST_Dump(postgis.ST_Split(a, s))).geom AS p FROM area, shore
                        ),
                        tests AS (
                            SELECT postgis.ST_SetSRID(postgis.ST_GeomFromGeoJSON(:points), 4326) AS t
                        ),
                        wet AS (
                            SELECT postgis.ST_Union(p) AS g FROM pieces, tests WHERE postgis.ST_Intersects(p, t)
                        )
                        INSERT INTO map_feature (property_id, kind, name, geom, source, source_ref, details, fetched_at)
                        SELECT :property, 'water', NULL, g, 'derived', 'coastline', :details::jsonb, now()
                          FROM wet
                         WHERE g IS NOT NULL
                        """)
                .param("area", area)
                .param("property", propertyId)
                .param("points", testPointsGeoJson)
                .param("details", detailsJson)
                .update();
    }

    public void insertParcel(UUID propertyId, String address, String ref, String detailsJson, String geoJson) {
        db.sql("""
                        INSERT INTO map_feature (property_id, kind, name, geom, source, source_ref, details, fetched_at)
                        SELECT :property, 'parcel', :address, g, 'wa_parcels', :ref, :details::jsonb, now()
                          FROM (SELECT postgis.ST_MakeValid(postgis.ST_SetSRID(postgis.ST_GeomFromGeoJSON(:shape), 4326)) AS g) x
                         WHERE NOT postgis.ST_IsEmpty(g)
                        """)
                .param("property", propertyId)
                .param("address", address)
                .param("ref", ref)
                .param("details", detailsJson)
                .param("shape", geoJson)
                .update();
    }

    // A contour line, cut to the pulled area, lightly simplified (about 30 cm)
    // and rounded off so it draws as a smooth curve rather than pixel steps
    public void insertContour(UUID propertyId, String lineGeoJson, String area, String name, String detailsJson) {
        db.sql("""
                        WITH g AS (
                            SELECT postgis.ST_Intersection(
                                       postgis.ST_ChaikinSmoothing(postgis.ST_SimplifyPreserveTopology(
                                           postgis.ST_SetSRID(postgis.ST_GeomFromGeoJSON(:line), 4326), 0.000003), 1),
                                       postgis.ST_SetSRID(postgis.ST_GeomFromGeoJSON(:area), 4326)) AS g
                        )
                        INSERT INTO map_feature (property_id, kind, name, geom, source, source_ref, details, fetched_at)
                        SELECT :property, 'contour', :name, postgis.ST_CollectionExtract(g, 2), 'usgs_3dep', 'dem',
                               :details::jsonb, now()
                          FROM g
                         WHERE NOT postgis.ST_IsEmpty(postgis.ST_CollectionExtract(g, 2))
                        """)
                .param("line", lineGeoJson)
                .param("area", area)
                .param("property", propertyId)
                .param("name", name)
                .param("details", detailsJson)
                .update();
    }

    // The height grid and its hillshade, replacing the park's previous one.
    // grid: 32-bit little-endian floats in meters, top row first.
    public void saveElevation(UUID propertyId, Elevation.Summary summary, byte[] grid, byte[] hillshadePng) {
        double[] b = summary.bbox3857();
        db.sql("""
                        INSERT INTO site_elevation (property_id, grid, width, height, bbox_3857, extent, metres_per_px,
                                                    min_ft, max_ft, contour_interval_ft, datum, source, hillshade_png,
                                                    fetched_at)
                        VALUES (:property, :grid, :width, :height, ARRAY[:x0, :y0, :x1, :y1]::float8[],
                                postgis.ST_Transform(postgis.ST_MakeEnvelope(:x0, :y0, :x1, :y1, 3857), 4326),
                                :perPx, :minFt, :maxFt, :interval, 'NAVD88', 'USGS 3DEP (best available)', :png, now())
                        ON CONFLICT (property_id) DO UPDATE SET
                            grid                = EXCLUDED.grid,
                            width               = EXCLUDED.width,
                            height              = EXCLUDED.height,
                            bbox_3857           = EXCLUDED.bbox_3857,
                            extent              = EXCLUDED.extent,
                            metres_per_px       = EXCLUDED.metres_per_px,
                            min_ft              = EXCLUDED.min_ft,
                            max_ft              = EXCLUDED.max_ft,
                            contour_interval_ft = EXCLUDED.contour_interval_ft,
                            datum               = EXCLUDED.datum,
                            source              = EXCLUDED.source,
                            hillshade_png       = EXCLUDED.hillshade_png,
                            fetched_at          = EXCLUDED.fetched_at
                        """)
                .param("property", propertyId)
                .param("grid", grid)
                .param("width", summary.width())
                .param("height", summary.height())
                .param("x0", b[0])
                .param("y0", b[1])
                .param("x1", b[2])
                .param("y1", b[3])
                .param("perPx", summary.metersPerPx())
                .param("minFt", summary.minFt())
                .param("maxFt", summary.maxFt())
                .param("interval", summary.contourIntervalFt())
                .param("png", hillshadePng)
                .update();
    }

    public boolean hasElevation(UUID propertyId) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM site_elevation WHERE property_id = :property)")
                .param("property", propertyId)
                .query(Boolean.class)
                .single();
    }

    public Optional<byte[]> findHillshade(UUID propertyId) {
        return db.sql("SELECT hillshade_png FROM site_elevation WHERE property_id = :property AND hillshade_png IS NOT NULL")
                .param("property", propertyId)
                .query(byte[].class)
                .optional();
    }

    /**
     * The parcels the lots sit on become one park_boundary row, and leave the neighbor parcels.
     * A parcel belongs to the park when at least 2% of the lot area sits on it.
     * site_map.boundary gets the boundary too: its largest piece, if the park's parcels do not touch.
     */
    public void makeParkBoundary(UUID propertyId) {
        db.sql("""
                        WITH lots AS (
                            SELECT postgis.ST_Union(postgis.ST_MakeValid(o.outline)) AS g
                              FROM lot_outline o
                              JOIN lot l ON l.uuid = o.lot_id
                             WHERE o.property_id = :property
                               AND l.deleted_at IS NULL
                        ),
                        ours AS (
                            SELECT f.uuid, postgis.ST_MakeValid(f.geom) AS geom
                              FROM map_feature f, lots
                             WHERE f.property_id = :property
                               AND f.kind = 'parcel'
                               AND f.deleted_at IS NULL
                               AND postgis.ST_Area(postgis.ST_Intersection(postgis.ST_MakeValid(f.geom), lots.g))
                                   > 0.02 * postgis.ST_Area(lots.g)
                        ),
                        merged AS (
                            SELECT postgis.ST_Union(geom) AS g, array_agg(uuid) AS ids FROM ours
                        )
                        INSERT INTO map_feature (property_id, kind, name, geom, source, source_ref, details, fetched_at)
                        SELECT :property, 'park_boundary', NULL, g, 'derived', 'parcels',
                               jsonb_build_object('parcels',
                                   (SELECT jsonb_agg(details ->> 'PARCEL_ID_NR') FROM map_feature WHERE uuid = ANY (ids))),
                               now()
                          FROM merged
                         WHERE g IS NOT NULL
                        """)
                .param("property", propertyId)
                .update();

        db.sql("""
                        DELETE FROM map_feature f
                         USING map_feature b
                         WHERE f.property_id = :property
                           AND f.kind = 'parcel'
                           AND b.property_id = :property
                           AND b.kind = 'park_boundary'
                           AND jsonb_exists(b.details -> 'parcels', f.details ->> 'PARCEL_ID_NR')
                        """)
                .param("property", propertyId)
                .update();

        db.sql("""
                        UPDATE site_map s
                           SET boundary = (
                                   SELECT d.geom
                                     FROM map_feature f
                                    CROSS JOIN LATERAL postgis.ST_Dump(f.geom) d
                                    WHERE f.property_id = :property
                                      AND f.kind = 'park_boundary'
                                      AND f.deleted_at IS NULL
                                      AND postgis.GeometryType(d.geom) = 'POLYGON'
                                    ORDER BY postgis.ST_Area(d.geom) DESC
                                    LIMIT 1)
                         WHERE s.property_id = :property
                        """)
                .param("property", propertyId)
                .update();
    }

    /**
     * Applies the office's renames and hides (map_feature_edit) to the rows a pull just rewrote.
     * Matched by the source's own id, such as osm:way/123.
     */
    public void applyEdits(UUID propertyId) {
        db.sql("""
                        UPDATE map_feature f
                           SET name = NULLIF(e.name, '')
                          FROM map_feature_edit e
                         WHERE f.property_id = :property
                           AND e.property_id = :property
                           AND f.source_ref = e.source_ref
                           AND f.source <> 'manual'
                           AND e.name IS NOT NULL
                        """)
                .param("property", propertyId)
                .update();
        db.sql("""
                        DELETE FROM map_feature f
                         USING map_feature_edit e
                         WHERE f.property_id = :property
                           AND e.property_id = :property
                           AND f.source_ref = e.source_ref
                           AND f.source <> 'manual'
                           AND e.hidden
                        """)
                .param("property", propertyId)
                .update();
    }

    /**
     * Ties each building to the lot it stands on. Runs after every pull and every map save,
     * since either can move lots or buildings. Returns how many lots have a home.
     *
     * <p>Every building gets:
     * <ul>
     *   <li>lot_id: the lot holding most of it, when that is at least half of it</li>
     *   <li>details.lot_share: how much of it is on that lot, 0 to 1</li>
     *   <li>details.role: 'home' for the biggest building on a lot, 'outbuilding' for others
     *       (sheds, carports). A hand-drawn building typed as anything but Home is never a home</li>
     *   <li>details.footprint: square feet, and the length and width of the smallest rectangle
     *       around it, so a single-wide reads about 14 x 66</li>
     * </ul>
     */
    public int linkBuildings(UUID propertyId) {
        db.sql("""
                        WITH b AS (
                            SELECT uuid,
                                   postgis.ST_MakeValid(geom) AS geom,
                                   coalesce(details ->> 'building_type', 'Home') = 'Home' AS can_be_home
                              FROM map_feature
                             WHERE property_id = :property AND kind = 'building' AND deleted_at IS NULL
                        ),
                        lots AS (
                            SELECT o.lot_id, postgis.ST_MakeValid(o.outline) AS outline
                              FROM lot_outline o
                              JOIN lot l ON l.uuid = o.lot_id
                             WHERE o.property_id = :property
                               AND l.deleted_at IS NULL
                        ),
                        overlap AS (
                            SELECT b.uuid, l.lot_id,
                                   postgis.ST_Area(postgis.ST_Intersection(b.geom, l.outline)::postgis.geography)
                                       / NULLIF(postgis.ST_Area(b.geom::postgis.geography), 0) AS share
                              FROM b
                              JOIN lots l ON postgis.ST_Intersects(b.geom, l.outline)
                        ),
                        best AS (
                            SELECT DISTINCT ON (uuid) uuid, lot_id, share
                              FROM overlap
                             ORDER BY uuid, share DESC
                        ),
                        linked AS (
                            SELECT uuid, lot_id, share FROM best WHERE share >= 0.5
                        ),
                        ranked AS (
                            SELECT k.uuid, k.lot_id, k.share, b.can_be_home,
                                   row_number() OVER (PARTITION BY k.lot_id
                                                      ORDER BY b.can_be_home DESC,
                                                               postgis.ST_Area(b.geom::postgis.geography) DESC) AS rank
                              FROM linked k
                              JOIN b USING (uuid)
                        ),
                        sizes AS (
                            -- Measured in Web Mercator, then shrunk by cos(latitude) to real meters
                            SELECT b.uuid,
                                   postgis.ST_Area(b.geom::postgis.geography) * 10.7639 AS sq_ft,
                                   postgis.ST_OrientedEnvelope(postgis.ST_Transform(b.geom, 3857)) AS env,
                                   cos(radians(postgis.ST_Y(postgis.ST_Centroid(b.geom)))) AS k
                              FROM b
                        ),
                        dims AS (
                            SELECT uuid, sq_ft,
                                   postgis.ST_Distance(postgis.ST_PointN(postgis.ST_ExteriorRing(env), 1),
                                                       postgis.ST_PointN(postgis.ST_ExteriorRing(env), 2)) * k * 3.28084 AS a,
                                   postgis.ST_Distance(postgis.ST_PointN(postgis.ST_ExteriorRing(env), 2),
                                                       postgis.ST_PointN(postgis.ST_ExteriorRing(env), 3)) * k * 3.28084 AS c
                              FROM sizes
                             WHERE postgis.GeometryType(env) = 'POLYGON'
                        )
                        UPDATE map_feature f
                           SET lot_id = r.lot_id,
                               details = (f.details - 'lot_id' - 'lot_share' - 'role' - 'footprint')
                                         || jsonb_strip_nulls(jsonb_build_object(
                                                'lot_share', round(r.share::numeric, 2),
                                                'role', CASE WHEN r.rank = 1 AND r.can_be_home THEN 'home'
                                                             WHEN r.rank IS NOT NULL THEN 'outbuilding' END,
                                                'footprint', jsonb_build_object(
                                                    'sq_ft', round(d.sq_ft::numeric),
                                                    'length_ft', round(greatest(d.a, d.c)::numeric, 1),
                                                    'width_ft', round(least(d.a, d.c)::numeric, 1))))
                          FROM b
                          LEFT JOIN ranked r USING (uuid)
                          LEFT JOIN dims d USING (uuid)
                         WHERE f.uuid = b.uuid
                        """)
                .param("property", propertyId)
                .update();
        return db.sql("""
                        SELECT count(*) FROM map_feature
                         WHERE property_id = :property AND kind = 'building' AND deleted_at IS NULL
                           AND details ->> 'role' = 'home'
                        """)
                .param("property", propertyId)
                .query(Integer.class)
                .single();
    }

    // How many rows of each kind the pull's sources hold now
    public Map<String, Integer> countsByKind(UUID propertyId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<KindCount> rows = db.sql("""
                        SELECT kind, count(*) AS n
                          FROM map_feature
                         WHERE property_id = :property AND source IN (:sources) AND deleted_at IS NULL
                           AND NOT (source = 'derived' AND source_ref = 'pull')
                         GROUP BY kind
                         ORDER BY kind
                        """)
                .param("property", propertyId)
                .param("sources", OWNED_SOURCES)
                .query(KindCount.class)
                .list();
        rows.forEach(row -> counts.put(row.kind(), row.n()));
        return counts;
    }

    // One row describing the pull itself: the area covered, when, and how it went.
    // A map drawn later uses the area as its frame.
    public void insertPullRecord(UUID propertyId, String area, String detailsJson) {
        db.sql("""
                        INSERT INTO map_feature (property_id, kind, name, geom, source, source_ref, details, fetched_at)
                        VALUES (:property, 'context_area', NULL,
                                postgis.ST_SetSRID(postgis.ST_GeomFromGeoJSON(:area), 4326),
                                'derived', 'pull', :details::jsonb, now())
                        """)
                .param("property", propertyId)
                .param("area", area)
                .param("details", detailsJson)
                .update();
    }

    public Optional<LastPull> findLastPull(UUID propertyId) {
        return db.sql("""
                        SELECT details::text AS details, fetched_at
                          FROM map_feature
                         WHERE property_id = :property AND kind = 'context_area' AND deleted_at IS NULL
                         ORDER BY fetched_at DESC
                         LIMIT 1
                        """)
                .param("property", propertyId)
                .query(LastPull.class)
                .optional();
    }

    // The license lines a printed map must carry, as a JSON list of text
    public void saveCredits(UUID propertyId, String creditsJson) {
        db.sql("""
                        UPDATE site_map
                           SET credits = ARRAY(SELECT jsonb_array_elements_text(:credits::jsonb))
                         WHERE property_id = :property
                        """)
                .param("property", propertyId)
                .param("credits", creditsJson)
                .update();
    }
}

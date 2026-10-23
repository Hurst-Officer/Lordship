-- ============================================================
-- V6: Site maps - where a lot is on the ground
--
-- Outlines are latitude/longitude, SRID 4326, [longitude, latitude] order.
-- Needs postgis, which lives in its own schema; see db/postgis-setup.sql.
-- Types and functions are written postgis.<name> on purpose. Flyway sets its
-- own search_path per connection, so a database-level one does not reach here.
-- Areas are never stored: ST_Area(outline::geography) * 10.7639 is square feet.
--
-- Mirrors the office map builder (Hurst-Office sql/001-003) as of 2026-10-02.
-- Spatial queries should wrap shapes in postgis.ST_MakeValid(...) first: the
-- office keeps self-crossing buildings out, but one bad shape in an
-- ST_Intersection or ST_Union still throws and fails the whole query.
-- ============================================================

-- PostGIS is NOT created here. Creating an extension needs superuser, and the
-- application's role is not one. A DBA installs it once per database, into its
-- own schema, with db/postgis-setup.sql. This checks it is there and says so by
-- name rather than failing on an unknown type three statements later.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'postgis') THEN
        RAISE EXCEPTION
            'postgis is not installed in this database. Run db/postgis-setup.sql as a superuser first.';
    END IF;
END $$;

-- One row per park that somebody has placed on the map.
CREATE TABLE site_map (
                          property_id  UUID PRIMARY KEY REFERENCES property(uuid),
                          boundary     postgis.geometry(Polygon, 4326),   -- from county parcels; null outside WA
                          imagery      TEXT,                      -- which satellite imagery it was placed against
                          credits      TEXT[] NOT NULL DEFAULT '{}', -- license lines a printed map must carry

    -- How the plat drawing was pinned to the globe. The map builder needs these
    -- to reopen a placed park. Pixel shapes per lot live in lot.shape_data.
                          plat_image_file_name TEXT,
                          plat_file_id         UUID,              -- the drawing itself, in document_file (made in V10, so no foreign key here)
                          plat_width           INT,
                          plat_height          INT,
                          image_to_world       JSONB,             -- pixel -> Web Mercator matrix
                          control_points       JSONB NOT NULL DEFAULT '[]'::jsonb, -- pixel/lng-lat pairs it was fitted to

                          placed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                          placed_by    UUID REFERENCES agent(uuid),
                          note         TEXT,
                          deleted_at   TIMESTAMPTZ
);

-- One row per lot with a shape. A lot without one simply has no row: lots are
-- created by people, and reading a map fills in the shapes it recognises.
CREATE TABLE lot_outline (
                             lot_id       UUID PRIMARY KEY REFERENCES lot(uuid),
                             property_id  UUID NOT NULL REFERENCES property(uuid),
                             outline      postgis.geometry(Polygon, 4326) NOT NULL,
                             source       TEXT NOT NULL DEFAULT 'MAP_BUILDER'
                                 CONSTRAINT lot_outline_source_must_be_known
                                     CHECK (source IN ('MAP_BUILDER', 'HAND_DRAWN', 'IMPORT')),
                             placed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- A ring that crosses itself has no inside, so it cannot be filled or
    -- measured. The map builder reports these; they must not reach a lease.
    -- (The office saves them anyway, so imports skip them.)
                             CONSTRAINT lot_outline_must_not_cross_itself CHECK (postgis.ST_IsValid(outline))
);

CREATE INDEX idx_lot_outline_per_property ON lot_outline (property_id);

-- Where to print a lot's number: the middle of the biggest circle that fits
-- inside it, measured in metres. Better than ST_PointOnSurface, which can land
-- against an edge or up the narrow leg of an L-shaped lot.
--   postgis.ST_Transform((postgis.ST_MaximumInscribedCircle(
--       postgis.ST_MakeValid(postgis.ST_Transform(outline, 3857)))).center, 4326)

-- What a map may hold besides lots. Seeded below; the office may add to it.
CREATE TABLE map_feature_kind (
                                  kind           TEXT PRIMARY KEY,
                                  label          TEXT NOT NULL,
                                  print_on_lease BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO map_feature_kind (kind, label, print_on_lease) VALUES
    ('road',          'Road',                TRUE),
    ('water',         'Water',               TRUE),
    ('stream',        'Stream',              TRUE),
    ('coastline',     'Shoreline',           TRUE),
    ('park_boundary', 'Park boundary',       TRUE),
    ('building',      'Building',            FALSE),  -- the tenant's own home is drawn by rule, not by this flag
    ('parcel',        'Neighbouring parcel', FALSE),  -- the park's own parcels are merged into park_boundary
    ('contour',       'Contour',             FALSE),
    ('light',         'Light',               FALSE),  -- a point; drawn as a fixed-size dot
    ('context_area',  'Area pulled',         FALSE);  -- the frame of the last pull, with its credits in details

-- Everything on a park map that is not a lot.
CREATE TABLE map_feature (
                             uuid         UUID PRIMARY KEY DEFAULT uuidv7(),
                             property_id  UUID NOT NULL REFERENCES property(uuid),
                             kind         TEXT NOT NULL REFERENCES map_feature_kind(kind),
                             name         TEXT,                    -- road name, pond name
                             geom         postgis.geometry(Geometry, 4326) NOT NULL,  -- a road can be a MultiLineString; draw every part
                             lot_id       UUID REFERENCES lot(uuid),  -- set on a building that stands on one lot
                             details      JSONB NOT NULL DEFAULT '{}'::jsonb,
                             source       TEXT NOT NULL
                                 CONSTRAINT map_feature_source_must_be_known
                                     CHECK (source IN ('osm', 'wa_parcels', 'usgs_3dep', 'derived', 'manual')),
                             source_ref   TEXT,
                             fetched_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                             deleted_at   TIMESTAMPTZ
);

CREATE INDEX idx_map_feature_per_property ON map_feature (property_id, kind) WHERE deleted_at IS NULL;

-- A re-pull replaces what it fetched and never touches hand-placed rows, which
-- is what makes 'manual' safe for light posts, well houses and hook-ups.
COMMENT ON COLUMN map_feature.source IS 'manual rows survive a re-pull; every other source is replaced by it';
COMMENT ON COLUMN map_feature.source_ref IS
    'the source''s own id, e.g. osm:way/123. Manual rows: ''manual'', or ''replaces:<ref>'' for a hand-corrected copy of a pulled row';
COMMENT ON COLUMN map_feature.details IS
    'how to draw it. Buildings: building_type, role (home/outbuilding), lot_share, footprint. Roads: class, width_m. Copies: replaces';

-- The office's renames and hides of PULLED features. A re-pull rewrites those
-- rows, so the change is kept here by source_ref and applied again after each
-- pull. Reshaping a pulled row makes a manual copy and hides the original here.
CREATE TABLE map_feature_edit (
                                  property_id  UUID NOT NULL REFERENCES property(uuid),
                                  source_ref   TEXT NOT NULL,           -- which pulled feature
                                  name         TEXT,                    -- new name; '' means no name; NULL means not renamed
                                  hidden       BOOLEAN,                 -- true leaves it out; NULL means not hidden
                                  edited_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  CONSTRAINT map_feature_edit_one_per_feature UNIQUE (property_id, source_ref)
);

-- The ground's height around a park, kept here rather than as files.
-- grid: 32-bit little-endian floats in metres (NAVD88), top (north) row first,
-- width x height of them. NaN means no data. bbox_3857 is xmin, ymin, xmax, ymax.
CREATE TABLE site_elevation (
                                property_id         UUID PRIMARY KEY REFERENCES property(uuid),
                                grid                BYTEA NOT NULL,
                                width               INT NOT NULL,
                                height              INT NOT NULL,
                                bbox_3857           DOUBLE PRECISION[] NOT NULL,
                                extent              postgis.geometry(Polygon, 4326) NOT NULL,
                                metres_per_px       REAL,
                                min_ft              REAL,
                                max_ft              REAL,
                                contour_interval_ft REAL,
                                datum               TEXT,
                                source              TEXT,
                                hillshade_png       BYTEA,
                                fetched_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- The small drawing of each park on the global map
-- ---------------------------------------------------------------------------

-- One small drawing per park, made from its lot shapes. The global page reads
-- these instead of every lot. Paths are SVG, in metres east and south of
-- middle, to 10 cm. Lot corners that nearly meet are merged into one point,
-- so neighboring lots share clean edges and the drawing stays uncrowded.
CREATE TABLE property_footprint (
                                    property_id   UUID PRIMARY KEY REFERENCES property(uuid),
                                    lot_count     INT NOT NULL,
                                    middle        postgis.geometry(Point, 4326) NOT NULL, -- the middle of the outline around the lots
                                    size_metres   DOUBLE PRECISION NOT NULL,              -- the longer side of that outline
                                    hull_svg      TEXT NOT NULL,  -- the outline around all the lots
                                    lots_svg      TEXT,
                                    refreshed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Parks whose drawing is out of date. Triggers add a park here when its lot
-- shapes change. The drawings are rebuilt the next time the global map is
-- read, so a map builder commit stays fast.
CREATE TABLE property_footprint_stale (
                                          property_id  UUID PRIMARY KEY REFERENCES property(uuid)
);

-- A shape in metres east and north of a point.
-- Web mercator metres grow by 1 / cos(latitude). Scaling by cos(latitude) undoes that.
CREATE FUNCTION metres_around(shape postgis.geometry, middle postgis.geometry)
    RETURNS postgis.geometry AS $$
    SELECT postgis.ST_Scale(
               postgis.ST_Translate(postgis.ST_Transform(shape, 3857),
                                    -postgis.ST_X(postgis.ST_Transform(middle, 3857)),
                                    -postgis.ST_Y(postgis.ST_Transform(middle, 3857))),
               cos(radians(postgis.ST_Y(middle))),
               cos(radians(postgis.ST_Y(middle))))
$$ LANGUAGE sql IMMUTABLE;

-- A shape as an SVG path, with relative moves and one decimal.
-- ST_AsSVG turns y around, because SVG's y runs down. An empty shape gives NULL.
CREATE FUNCTION svg_path(shape postgis.geometry) RETURNS TEXT AS $$
    SELECT NULLIF(postgis.ST_AsSVG(shape, 1, 1), '')
$$ LANGUAGE sql IMMUTABLE;

-- One park's lots in metres around middle, with corners closer than 4 m merged.
-- Steps:
--   1. list every corner of every live lot
--   2. group corners that lie within 4 m of each other (a chain counts as one group)
--   3. move each corner to the middle of its group
--   4. rebuild each lot from its moved corners, dropping repeats
-- Corners across a road are more than 4 m apart, so they stay separate.
-- A lot that shrinks below three corners is left out.
CREATE FUNCTION merged_lot_shapes(target UUID, middle postgis.geometry) RETURNS postgis.geometry AS $$
    WITH corners AS (
        SELECT o.lot_id,
               d.path[1] AS position,
               d.geom
          FROM lot_outline o
          JOIN lot l ON l.uuid = o.lot_id
         CROSS JOIN LATERAL postgis.ST_DumpPoints(postgis.ST_ExteriorRing(metres_around(o.outline, middle))) d
         WHERE o.property_id = target
           AND l.deleted_at IS NULL
           AND d.path[1] < postgis.ST_NPoints(postgis.ST_ExteriorRing(o.outline))  -- the last point repeats the first
    ),
    grouped AS (
        SELECT corners.*, postgis.ST_ClusterDBSCAN(geom, 4.0, 1) OVER () AS group_id
          FROM corners
    ),
    group_middles AS (
        SELECT group_id, postgis.ST_Centroid(postgis.ST_Collect(geom)) AS point
          FROM grouped
         GROUP BY group_id
    ),
    rings AS (
        SELECT g.lot_id,
               postgis.ST_RemoveRepeatedPoints(postgis.ST_MakeLine(m.point ORDER BY g.position)) AS line
          FROM grouped g
          JOIN group_middles m ON m.group_id = g.group_id
         GROUP BY g.lot_id
    )
    SELECT postgis.ST_SimplifyPreserveTopology(
               postgis.ST_Collect(postgis.ST_MakePolygon(postgis.ST_AddPoint(line, postgis.ST_StartPoint(line)))),
               0.3)
      FROM rings
     WHERE postgis.ST_NPoints(line) >= 3
$$ LANGUAGE sql STABLE;

-- Rebuilds one park's drawing. A park with no live lot shapes gets no row.
CREATE FUNCTION refresh_property_footprint(target UUID) RETURNS void AS $$
DECLARE
    hull_degrees  postgis.geometry;
    mid           postgis.geometry;
    hull_metres   postgis.geometry;
    lot_total     INT;
BEGIN
    DELETE FROM property_footprint WHERE property_id = target;

    SELECT postgis.ST_ConvexHull(postgis.ST_Collect(o.outline)), count(*)
      INTO hull_degrees, lot_total
      FROM lot_outline o
      JOIN lot l ON l.uuid = o.lot_id
     WHERE o.property_id = target
       AND l.deleted_at IS NULL;

    IF lot_total = 0 THEN
        RETURN;
    END IF;

    mid := postgis.ST_Centroid(postgis.ST_Envelope(hull_degrees));
    hull_metres := metres_around(hull_degrees, mid);

    INSERT INTO property_footprint (property_id, lot_count, middle, size_metres, hull_svg, lots_svg)
    VALUES (target,
            lot_total,
            mid,
            greatest(postgis.ST_XMax(hull_metres) - postgis.ST_XMin(hull_metres),
                     postgis.ST_YMax(hull_metres) - postgis.ST_YMin(hull_metres),
                     1),
            svg_path(hull_metres),
            svg_path(merged_lot_shapes(target, mid)));
END;
$$ LANGUAGE plpgsql;

-- Rebuilds every out-of-date drawing and returns how many it rebuilt.
-- Each park leaves the list as it is rebuilt, so two readers never rebuild the same park.
CREATE FUNCTION refresh_stale_footprints() RETURNS INT AS $$
DECLARE
    target   UUID;
    rebuilt  INT := 0;
BEGIN
    FOR target IN DELETE FROM property_footprint_stale RETURNING property_id LOOP
        PERFORM refresh_property_footprint(target);
        rebuilt := rebuilt + 1;
    END LOOP;
    RETURN rebuilt;
END;
$$ LANGUAGE plpgsql;

-- Marks the park of the changed row as out of date
CREATE FUNCTION mark_footprint_stale() RETURNS trigger AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        INSERT INTO property_footprint_stale (property_id) VALUES (OLD.property_id) ON CONFLICT DO NOTHING;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        INSERT INTO property_footprint_stale (property_id) VALUES (NEW.property_id) ON CONFLICT DO NOTHING;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER lot_outline_marks_footprint_stale
    AFTER INSERT OR UPDATE OR DELETE ON lot_outline
    FOR EACH ROW EXECUTE FUNCTION mark_footprint_stale();

-- A removed lot leaves the drawing too
CREATE TRIGGER lot_removal_marks_footprint_stale
    AFTER UPDATE OF deleted_at ON lot
    FOR EACH ROW EXECUTE FUNCTION mark_footprint_stale();

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

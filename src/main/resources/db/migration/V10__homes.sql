-- ============================================================
-- V10: Homes
-- ============================================================
-- Constraint names are user-facing: Postgres reports a violation as
-- 'violates check constraint "<name>"', and ApiExceptionHandler passes that
-- straight through as the 400. Name each rule so the message says what is wrong.

CREATE TABLE mobile_home (
         uuid UUID PRIMARY KEY DEFAULT uuidv7(),
         name TEXT,
         lot_id UUID REFERENCES lot(uuid),
         estimated_value NUMERIC(12,2)
             CONSTRAINT mobile_home_estimated_value_must_not_be_negative CHECK (estimated_value >= 0),
         estimated_value_on DATE,
         model_year INT
             CONSTRAINT mobile_home_model_year_must_be_1930_to_2500 CHECK (model_year BETWEEN 1930 AND 2500),
         make TEXT,
         model TEXT,
         bedroom_count INT
             CONSTRAINT mobile_home_bedroom_count_must_not_be_negative CHECK (bedroom_count >= 0),
         bathroom_count NUMERIC(3,1)
             CONSTRAINT mobile_home_bathroom_count_must_not_be_negative CHECK (bathroom_count >= 0),
         width  NUMERIC(6,2)
             CONSTRAINT mobile_home_width_must_be_greater_than_zero CHECK (width > 0),
         length NUMERIC(6,2)
             CONSTRAINT mobile_home_length_must_be_greater_than_zero CHECK (length > 0),
         dimensions_units TEXT
             CONSTRAINT mobile_home_dimensions_units_must_be_ft_or_m CHECK (dimensions_units IN ('FT','M'))
             NOT NULL DEFAULT 'FT',
         sections INT
             CONSTRAINT mobile_home_sections_must_be_1_to_4 CHECK (sections > 0 AND sections < 5),
         condition TEXT
             CONSTRAINT mobile_home_condition_must_be_a_known_grade
             CHECK (condition IN ('NEW','EXCELLENT','GOOD','FAIR','POOR','UNINHABITABLE','DEMO')),
         appearance TEXT,           -- a description of the unit "blue paint and windows with wood shutters"
         note TEXT,
         parcel TEXT,               -- optional (not all homes have their own parcels)
         vin TEXT,                  -- optional (not all homes have their own vin). Should be as printed; multisection homes list both
         park_owned BOOLEAN NOT NULL DEFAULT FALSE,
         created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
         created_by UUID REFERENCES agent(uuid),
         deleted_at TIMESTAMPTZ,

         CONSTRAINT mobile_home_valuation_date_needs_a_value CHECK (
             estimated_value_on IS NULL OR estimated_value IS NOT NULL
        )
);


CREATE TABLE stick_built (
                             uuid UUID PRIMARY KEY DEFAULT uuidv7(),
                             name TEXT,                                  -- e.g., "Manager House", "Apt 2B"
                             lot_id UUID REFERENCES lot(uuid),
                             year_built INT
                                 CONSTRAINT stick_built_year_built_must_be_1800_to_2500
                                 CHECK (year_built BETWEEN 1800 AND 2500),

                             structure_type TEXT
                                 CONSTRAINT stick_built_structure_type_must_be_a_known_type
                                 CHECK (structure_type IN
                                        ('APARTMENT','SINGLE_FAMILY','GARAGE','SHOP','OUTBUILDING')),

                             floor INT
                                 CONSTRAINT stick_built_floor_must_not_be_zero CHECK (floor != 0),  -- 1 ground, -1 basement

                             bedroom_count INT
                                 CONSTRAINT stick_built_bedroom_count_must_not_be_negative CHECK (bedroom_count >= 0),
                             bathroom_count NUMERIC(3,1)
                                 CONSTRAINT stick_built_bathroom_count_must_not_be_negative CHECK (bathroom_count >= 0),
                             area NUMERIC(8,2)
                                 CONSTRAINT stick_built_area_must_be_greater_than_zero CHECK (area > 0),
                             area_units TEXT
                                 CONSTRAINT stick_built_area_units_must_be_sqft_or_sqm
                                 CHECK (area_units IN ('SQFT','SQM'))
                                 NOT NULL DEFAULT 'SQFT',

    -- No condition column, unlike mobile_home: whether a space can be rented is a
    -- lot fact, and outstanding work belongs to a maintenance system that does not
    -- exist yet. Not an oversight.
                             appearance TEXT,                            -- "blue door, second floor balcony"
                             park_owned BOOLEAN NOT NULL DEFAULT TRUE,   -- false for a house on a deeded lot
                             note TEXT,

                             created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                             created_by UUID REFERENCES agent(uuid),
                             deleted_at TIMESTAMPTZ
);


-- ── Indexes ──────────────────────────────────────────────────────────────────

CREATE INDEX idx_mobile_home_lot
    ON mobile_home(lot_id) WHERE deleted_at IS NULL;

CREATE INDEX idx_mobile_home_vin
    ON mobile_home(UPPER(vin)) WHERE vin IS NOT NULL AND deleted_at IS NULL;

-- One lot is one rentable unit, so a stick built is unique on its lot. Unlike a
-- mobile home there is no swap window -- a house is not hauled off and replaced
-- the same afternoon. The trigger below refuses this case first, with a message a
-- person can read; this index is the guarantee underneath it.
-- Serves lookups by lot as well, so no second index on lot_id.
CREATE UNIQUE INDEX uq_stick_built_lot
    ON stick_built(lot_id) WHERE deleted_at IS NULL;


-- ── One structure per lot ────────────────────────────────────────────────────

-- A lot holds mobile homes or a stick built, never both, and never two stick
-- builts. Two functions because each table has to ask about the other, and the
-- rule only holds if both ask. FOR UPDATE on the lot serializes concurrent
-- inserts, so the counts are not a race.

CREATE OR REPLACE FUNCTION stick_built_lot_is_free()
    RETURNS TRIGGER AS $$
DECLARE
    homes  INT;
    others INT;
BEGIN
    IF NEW.lot_id IS NULL OR NEW.deleted_at IS NOT NULL THEN
        RETURN NEW;
    END IF;

    PERFORM 1 FROM lot WHERE uuid = NEW.lot_id FOR UPDATE;

    SELECT count(*) INTO homes
    FROM mobile_home
    WHERE lot_id = NEW.lot_id AND deleted_at IS NULL;

    IF homes > 0 THEN
        RAISE EXCEPTION 'Lot % already holds a mobile home', NEW.lot_id
            USING ERRCODE = '23514';
    END IF;

    -- uq_stick_built_lot would catch this too, but as a duplicate key with no
    -- wording anyone outside the database would want to read.
    SELECT count(*) INTO others
    FROM stick_built
    WHERE lot_id = NEW.lot_id AND deleted_at IS NULL AND uuid <> NEW.uuid;

    IF others > 0 THEN
        RAISE EXCEPTION 'Lot % already holds a stick built', NEW.lot_id
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION mobile_home_lot_has_no_stick_built()
    RETURNS TRIGGER AS $$
DECLARE
    existing INT;
BEGIN
    IF NEW.lot_id IS NULL OR NEW.deleted_at IS NOT NULL THEN
        RETURN NEW;
    END IF;

    PERFORM 1 FROM lot WHERE uuid = NEW.lot_id FOR UPDATE;

    SELECT count(*) INTO existing
    FROM stick_built
    WHERE lot_id = NEW.lot_id AND deleted_at IS NULL;

    IF existing > 0 THEN
        RAISE EXCEPTION 'Lot % already holds a stick built', NEW.lot_id
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- deleted_at is in the column list because clearing it puts a structure back onto
-- a lot, which the insert path never sees.
CREATE TRIGGER trg_stick_built_lot_is_free
    BEFORE INSERT OR UPDATE OF lot_id, deleted_at ON stick_built
    FOR EACH ROW EXECUTE FUNCTION stick_built_lot_is_free();

CREATE TRIGGER trg_mobile_home_lot_has_no_stick_built
    BEFORE INSERT OR UPDATE OF lot_id, deleted_at ON mobile_home
    FOR EACH ROW EXECUTE FUNCTION mobile_home_lot_has_no_stick_built();

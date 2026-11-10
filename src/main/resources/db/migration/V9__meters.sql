-- ============================================================
-- V9: Meters and utility bills
-- ============================================================
-- A meter main is a meter a utility company bills the park for. It belongs to
-- the property, never to a lot. A submeter is the park's own meter: it hangs
-- off one main and feeds one or more lots (two lots can share a submeter).
-- Every link is dated, so the tree can be read as it stood on any day.
--
-- Reads are kept for both: the reads printed on a provider's bill, and the
-- reads staff take. Usage is never stored; it is worked out from each read and
-- the one before it, with the meter's settings (unit, multiplier, dial) in force
-- on the read's day. Settings are dated, so correcting them from a day on never
-- changes what was worked out before that day.
--
-- The rate a tenant pays is worked out, never typed in. For one property,
-- service, billing month and rate group: the bill lines passed on, added up,
-- divided by the usage of the mains those bills are for. A bill keeps the rate
-- group it was entered under, and each line keeps its own billing month (water
-- and sewer on one bill can land in different months). A submeter pays
-- rate x its own usage, shared evenly by the occupied lots it feeds; the owner
-- bears the rest. A service with no submeters is split evenly (RUBS). The split
-- and the posting are not stored yet: they wait for tenancies and the billing run.

-- The exclusion constraints below compare UUIDs with = inside a GiST index.
-- btree_gist is a trusted extension (PostgreSQL 13+), so the database owner may
-- create it without superuser.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TYPE meter_type AS ENUM ('WATER', 'POWER');

-- The unit a meter or bill counts in. Water is converted to gallons and power
-- is kWh before money is worked out: CF = 1728/231 gal, CCF = 100 CF, KGAL = 1000 gal.
CREATE TYPE meter_unit AS ENUM ('GAL', 'KGAL', 'CF', 'CCF', 'KWH');

-- What a bill line is for. The line joins that service's rate. A fee billed
-- through the water bill (an ambulance fee, a base charge) is a WATER line.
CREATE TYPE utility_service AS ENUM ('WATER', 'SEWER', 'POWER', 'TRASH');

-- READ: an ordinary read. ROLLOVER: the dial passed dial_max and started over
--   since the read before (a person confirms it; a lower READ is flagged instead).
-- START: a meter's first read, or its first after a repair or reset; usage is
--   never counted across it.
CREATE TYPE meter_read_kind AS ENUM ('READ', 'ROLLOVER', 'START');

-- STAFF: read by the park. PROVIDER: printed on a utility company's bill.
CREATE TYPE meter_read_source AS ENUM ('STAFF', 'PROVIDER');

-- A meter's unit must suit its type
CREATE FUNCTION meter_unit_suits_type(t meter_type, u meter_unit) RETURNS boolean
    LANGUAGE sql IMMUTABLE AS $$
SELECT CASE t WHEN 'POWER' THEN u = 'KWH' ELSE u <> 'KWH' END
$$;

-- Lets a meter name a lot of its own property only (composite foreign key)
ALTER TABLE lot ADD CONSTRAINT lot_uuid_property_uq UNIQUE (uuid, property_id);


-- ── Rate groups ──────────────────────────────────────────────────────────────
-- Mains with no group share one rate per service: the park's default, which is
-- how the rent rolls work today. Put mains in a group when they must keep a rate
-- of their own: two providers, a phase on another city's lines, and so on.

CREATE TABLE utility_rate_group (
                                    uuid        UUID PRIMARY KEY DEFAULT uuidv7(),
                                    property_id UUID NOT NULL REFERENCES property(uuid),
                                    name        TEXT NOT NULL CONSTRAINT utility_rate_group_name_must_not_be_blank CHECK (length(trim(name)) > 0), -- e.g. "Phase 2 (County)"
                                    notes       TEXT,
                                    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                                    deleted_at  TIMESTAMPTZ,
                                    CONSTRAINT utility_rate_group_property_uq UNIQUE (uuid, property_id)
);

CREATE UNIQUE INDEX uq_utility_rate_group_name ON utility_rate_group (property_id, lower(trim(name))) WHERE deleted_at IS NULL;


-- ── Meter mains ──────────────────────────────────────────────────────────────

CREATE TABLE meter_main (
                            uuid           UUID PRIMARY KEY DEFAULT uuidv7(),
                            property_id    UUID NOT NULL REFERENCES property(uuid),
                            utility_type   meter_type NOT NULL,
                            rate_group_id  UUID,                  -- NULL: the park's default group
                            title          TEXT NOT NULL CONSTRAINT meter_main_title_must_not_be_blank CHECK (length(trim(title)) > 0), -- e.g. "North main"
                            provider       TEXT,                  -- who bills it, e.g. "City of Port Orchard"
                            account_number TEXT,                  -- the park's account with the provider
                            serial_number  TEXT,
                            point_x        DOUBLE PRECISION,      -- where it sits on the park's site map; set by the map
                            point_y        DOUBLE PRECISION,
                            installed_on   DATE,
                            removed_on     DATE,                  -- taken out of service (not a mistake: that is deleted_at)
                            notes          TEXT,
                            created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                            deleted_at     TIMESTAMPTZ,
                            FOREIGN KEY (rate_group_id, property_id) REFERENCES utility_rate_group (uuid, property_id),
                            CONSTRAINT meter_main_removed_after_installed CHECK (removed_on IS NULL OR installed_on IS NULL OR removed_on >= installed_on),
                            CONSTRAINT meter_main_point_needs_both CHECK ((point_x IS NULL) = (point_y IS NULL)),
                            CONSTRAINT meter_main_identity_uq UNIQUE (uuid, property_id, utility_type), -- targets of the composite keys below
                            CONSTRAINT meter_main_property_uq UNIQUE (uuid, property_id)
);

CREATE INDEX idx_meter_main_property ON meter_main (property_id) WHERE deleted_at IS NULL;


-- ── Submeters ────────────────────────────────────────────────────────────────

CREATE TABLE submeter (
                          uuid          UUID PRIMARY KEY DEFAULT uuidv7(),
                          property_id   UUID NOT NULL REFERENCES property(uuid),
                          utility_type  meter_type NOT NULL,
                          title         TEXT,               -- optional; Glade names it by its lots when blank
                          serial_number TEXT,
                          point_x       DOUBLE PRECISION,
                          point_y       DOUBLE PRECISION,
                          installed_on  DATE,
                          removed_on    DATE,
                          notes         TEXT,
                          created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                          updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                          deleted_at    TIMESTAMPTZ,
                          CONSTRAINT submeter_removed_after_installed CHECK (removed_on IS NULL OR installed_on IS NULL OR removed_on >= installed_on),
                          CONSTRAINT submeter_point_needs_both CHECK ((point_x IS NULL) = (point_y IS NULL)),
                          CONSTRAINT submeter_identity_uq UNIQUE (uuid, property_id, utility_type),
                          CONSTRAINT submeter_property_uq UNIQUE (uuid, property_id)
);

CREATE INDEX idx_submeter_property ON submeter (property_id) WHERE deleted_at IS NULL;

-- A serial number names one live meter, across every park
CREATE UNIQUE INDEX uq_meter_main_serial ON meter_main (lower(trim(serial_number))) WHERE deleted_at IS NULL AND serial_number IS NOT NULL;
CREATE UNIQUE INDEX uq_submeter_serial   ON submeter   (lower(trim(serial_number))) WHERE deleted_at IS NULL AND serial_number IS NOT NULL;


-- ── Meter settings ───────────────────────────────────────────────────────────
-- How to read a meter's dial, from a day on. Every meter has one setting with
-- no start day (it holds from the first read); a correction or a change adds a
-- dated one, so reads before that day keep the usage they always had.
-- A read uses the setting in force on its own day.

CREATE TABLE meter_setting (
                               uuid           UUID PRIMARY KEY DEFAULT uuidv7(),
                               main_id        UUID,
                               submeter_id    UUID,
                               property_id    UUID NOT NULL,
                               utility_type   meter_type NOT NULL,
                               effective_from DATE,                -- NULL: from the meter's first read
                               unit           meter_unit NOT NULL, -- what the dial counts in
                               multiplier     NUMERIC(16,6) NOT NULL DEFAULT 1 -- dial difference x multiplier = usage in unit (a dial that counts tens of gallons: 10)
                                   CONSTRAINT meter_setting_multiplier_must_be_positive CHECK (multiplier > 0),
                               dial_max       BIGINT NOT NULL DEFAULT 99999 -- the largest number the dial shows before it starts over at 0
                                   CONSTRAINT meter_setting_dial_max_must_be_positive CHECK (dial_max > 0),
                               notes          TEXT,                -- why it changed
                               created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                               deleted_at     TIMESTAMPTZ,
                               FOREIGN KEY (main_id, property_id, utility_type) REFERENCES meter_main (uuid, property_id, utility_type),
                               FOREIGN KEY (submeter_id, property_id, utility_type) REFERENCES submeter (uuid, property_id, utility_type),
                               CONSTRAINT meter_setting_is_of_one_meter CHECK ((main_id IS NULL) <> (submeter_id IS NULL)),
                               CONSTRAINT meter_setting_unit_must_suit_type CHECK (meter_unit_suits_type(utility_type, unit))
);

-- One live setting per meter per start day (and one with no start day)
CREATE UNIQUE INDEX uq_meter_setting_main ON meter_setting (main_id, effective_from) NULLS NOT DISTINCT
    WHERE deleted_at IS NULL AND main_id IS NOT NULL;
CREATE UNIQUE INDEX uq_meter_setting_submeter ON meter_setting (submeter_id, effective_from) NULLS NOT DISTINCT
    WHERE deleted_at IS NULL AND submeter_id IS NOT NULL;
CREATE INDEX idx_meter_setting_property ON meter_setting (property_id) WHERE deleted_at IS NULL;


-- ── The tree: main → submeters → lots ────────────────────────────────────────
-- property_id and utility_type are carried on each link so the composite keys
-- prove both ends are in the same park and carry the same utility.
-- effective_to is the first day the link no longer holds (NULL: still holds).

CREATE TABLE submeter_main (
                               uuid           UUID PRIMARY KEY DEFAULT uuidv7(),
                               submeter_id    UUID NOT NULL,
                               main_id        UUID NOT NULL,
                               property_id    UUID NOT NULL,
                               utility_type   meter_type NOT NULL,
                               effective_from DATE NOT NULL,
                               effective_to   DATE,
                               created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                               deleted_at     TIMESTAMPTZ,
                               FOREIGN KEY (submeter_id, property_id, utility_type) REFERENCES submeter (uuid, property_id, utility_type),
                               FOREIGN KEY (main_id, property_id, utility_type) REFERENCES meter_main (uuid, property_id, utility_type),
                               CONSTRAINT submeter_main_ends_after_it_starts CHECK (effective_to IS NULL OR effective_to > effective_from),
                               -- one main per submeter on any day
                               CONSTRAINT submeter_main_one_main_at_a_time EXCLUDE USING gist (
                                   submeter_id WITH =,
                                   daterange(effective_from, effective_to, '[)') WITH &&
                                   ) WHERE (deleted_at IS NULL)
);

CREATE INDEX idx_submeter_main_main ON submeter_main (main_id) WHERE deleted_at IS NULL;

CREATE TABLE submeter_lot (
                              uuid           UUID PRIMARY KEY DEFAULT uuidv7(),
                              submeter_id    UUID NOT NULL,
                              lot_id         UUID NOT NULL,
                              property_id    UUID NOT NULL,
                              utility_type   meter_type NOT NULL,
                              effective_from DATE NOT NULL,
                              effective_to   DATE,
                              created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                              deleted_at     TIMESTAMPTZ,
                              FOREIGN KEY (submeter_id, property_id, utility_type) REFERENCES submeter (uuid, property_id, utility_type),
                              FOREIGN KEY (lot_id, property_id) REFERENCES lot (uuid, property_id),
                              CONSTRAINT submeter_lot_ends_after_it_starts CHECK (effective_to IS NULL OR effective_to > effective_from),
                              -- a lot has one submeter per utility on any day; a submeter may feed several lots
                              CONSTRAINT submeter_lot_one_per_utility EXCLUDE USING gist (
                                  lot_id WITH =,
                                  utility_type WITH =,
                                  daterange(effective_from, effective_to, '[)') WITH &&
                                  ) WHERE (deleted_at IS NULL)
);

CREATE INDEX idx_submeter_lot_lot ON submeter_lot (lot_id) WHERE deleted_at IS NULL;


-- ── Reads ────────────────────────────────────────────────────────────────────
-- A read is of a main or of a submeter, never both. One read per meter per day:
-- a correction edits the read (the history bars keep the old value).

CREATE TABLE meter_read (
                            uuid         UUID PRIMARY KEY DEFAULT uuidv7(),
                            main_id      UUID REFERENCES meter_main(uuid),
                            submeter_id  UUID REFERENCES submeter(uuid),
                            read_on      DATE NOT NULL,
                            reading      BIGINT NOT NULL CONSTRAINT meter_read_reading_must_not_be_negative CHECK (reading >= 0), -- the number on the dial
                            kind         meter_read_kind NOT NULL DEFAULT 'READ',
                            source       meter_read_source NOT NULL DEFAULT 'STAFF',
                            is_estimated BOOLEAN NOT NULL DEFAULT FALSE, -- providers mark estimated reads too
                            notes        TEXT,
                            created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                            deleted_at   TIMESTAMPTZ,
                            CONSTRAINT meter_read_is_of_one_meter CHECK ((main_id IS NULL) <> (submeter_id IS NULL)),
                            CONSTRAINT meter_read_main_uq UNIQUE (uuid, main_id) -- lets a bill line prove its reads are of its main
);

CREATE UNIQUE INDEX uq_meter_read_main_day     ON meter_read (main_id, read_on)     WHERE deleted_at IS NULL AND main_id IS NOT NULL;
CREATE UNIQUE INDEX uq_meter_read_submeter_day ON meter_read (submeter_id, read_on) WHERE deleted_at IS NULL AND submeter_id IS NOT NULL;


-- ── Utility bills ────────────────────────────────────────────────────────────
-- One row per bill the park receives, with as much of what is printed on it as
-- staff can enter. A bill with no main (trash, or a service with no meter) has
-- no meter lines.

CREATE TABLE utility_bill (
                              uuid          UUID PRIMARY KEY DEFAULT uuidv7(),
                              property_id   UUID NOT NULL REFERENCES property(uuid),
                              rate_group_id UUID,                   -- the rate group it was entered under; NULL: the park's default
                              provider      TEXT,                   -- as printed; a bill with meters also has the mains' providers
                              account_number TEXT,
                              statement_date DATE,
                              period_start  DATE NOT NULL,          -- service dates printed on the bill
                              period_end    DATE NOT NULL,
                              printed_total NUMERIC(12,2),          -- the bill's total for this period, to check the lines against
                              document_file UUID,                   -- the scanned bill; key added in V10, where document_file is made
                              notes         TEXT,
                              created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                              updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                              deleted_at    TIMESTAMPTZ,
                              FOREIGN KEY (rate_group_id, property_id) REFERENCES utility_rate_group (uuid, property_id),
                              CONSTRAINT utility_bill_period_end_must_follow_start CHECK (period_end > period_start),
                              CONSTRAINT utility_bill_property_uq UNIQUE (uuid, property_id)
);

CREATE INDEX idx_utility_bill_property_period ON utility_bill (property_id, period_end) WHERE deleted_at IS NULL;

-- A main the bill is for, with the reads and usage printed for it. A sewer
-- district's bill that is worked out from the city's water main names that main
-- too. Usage is the printed usage when there is one, otherwise the reads' difference.
CREATE TABLE utility_bill_meter (
                                    uuid            UUID PRIMARY KEY DEFAULT uuidv7(),
                                    bill_id         UUID NOT NULL,
                                    main_id         UUID NOT NULL,
                                    property_id     UUID NOT NULL,
                                    prior_read_id   UUID,
                                    current_read_id UUID,
                                    printed_usage   NUMERIC(18,4) CONSTRAINT utility_bill_meter_usage_must_not_be_negative CHECK (printed_usage >= 0),
                                    printed_unit    meter_unit,   -- the bill may count in another unit than the dial (e.g. KGAL)
                                    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                                    deleted_at      TIMESTAMPTZ,
                                    FOREIGN KEY (bill_id, property_id) REFERENCES utility_bill (uuid, property_id),
                                    FOREIGN KEY (main_id, property_id) REFERENCES meter_main (uuid, property_id),
                                    FOREIGN KEY (prior_read_id, main_id) REFERENCES meter_read (uuid, main_id),
                                    FOREIGN KEY (current_read_id, main_id) REFERENCES meter_read (uuid, main_id),
                                    CONSTRAINT utility_bill_meter_has_usage CHECK (printed_usage IS NOT NULL OR (prior_read_id IS NOT NULL AND current_read_id IS NOT NULL)),
                                    CONSTRAINT utility_bill_meter_unit_with_usage CHECK ((printed_usage IS NULL) = (printed_unit IS NULL)),
                                    CONSTRAINT utility_bill_meter_reads_differ CHECK (prior_read_id <> current_read_id)
);

-- A main is listed once per bill
CREATE UNIQUE INDEX uq_utility_bill_meter_once ON utility_bill_meter (bill_id, main_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_utility_bill_meter_main ON utility_bill_meter (main_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_utility_bill_meter_prior_read ON utility_bill_meter (prior_read_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_utility_bill_meter_current_read ON utility_bill_meter (current_read_id) WHERE deleted_at IS NULL;

-- A line as printed on the bill: usage charge, base charge, ambulance fee, late fee...
-- passed_on: whether it goes into the rate tenants pay (a late fee the park
-- incurred would not).
CREATE TABLE utility_bill_line (
                                   uuid         UUID PRIMARY KEY DEFAULT uuidv7(),
                                   bill_id      UUID NOT NULL REFERENCES utility_bill(uuid),
                                   service      utility_service NOT NULL,
                                   description  TEXT NOT NULL CONSTRAINT utility_bill_line_description_must_not_be_blank CHECK (length(trim(description)) > 0),
                                   amount       NUMERIC(12,2) NOT NULL, -- may be negative (a credit)
                                   printed_rate NUMERIC(18,8),          -- the rate printed beside it, if any
                                   rate_unit    meter_unit,             -- per what (e.g. per CCF, per KGAL)
                                   passed_on    BOOLEAN NOT NULL DEFAULT TRUE,
                                   billing_month DATE NOT NULL,         -- the rent month this line's charge lands in (1st of the month)
                                   sort_order   INT NOT NULL,           -- the order printed on the bill
                                   created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                                   deleted_at   TIMESTAMPTZ,
                                   CONSTRAINT utility_bill_line_rate_needs_unit CHECK (printed_rate IS NULL OR rate_unit IS NOT NULL),
                                   CONSTRAINT utility_bill_line_billing_month_is_a_first CHECK (extract(day FROM billing_month) = 1)
);

CREATE INDEX idx_utility_bill_line_bill ON utility_bill_line (bill_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_utility_bill_line_month ON utility_bill_line (billing_month) WHERE deleted_at IS NULL;


-- ── Each park's cycle ────────────────────────────────────────────────────────
-- Set by the GMs in Glade. Example: billed every 2 months, in even months, two
-- months after the service period ends: cycle_months 2, anchor_month 2, lag_months 2.
-- The app proposes each bill line's billing_month from this; a person can still change it.

CREATE TABLE utility_cycle (
                               uuid           UUID PRIMARY KEY DEFAULT uuidv7(),
                               property_id    UUID NOT NULL REFERENCES property(uuid),
                               service        utility_service NOT NULL,
                               cycle_months   SMALLINT NOT NULL CONSTRAINT utility_cycle_months_divide_the_year CHECK (cycle_months IN (1, 2, 3, 4, 6, 12)),
                               anchor_month   SMALLINT NOT NULL CONSTRAINT utility_cycle_anchor_1_to_12 CHECK (anchor_month BETWEEN 1 AND 12), -- any month that carries a charge
                               lag_months     SMALLINT NOT NULL CONSTRAINT utility_cycle_lag_0_to_12 CHECK (lag_months BETWEEN 0 AND 12),     -- months from the end of the service period to the billing month
                               read_day       SMALLINT CONSTRAINT utility_cycle_read_day_1_to_31 CHECK (read_day BETWEEN 1 AND 31),          -- when submeters are due to be read
                               effective_from DATE NOT NULL CONSTRAINT utility_cycle_starts_on_a_first CHECK (extract(day FROM effective_from) = 1),
                               notes          TEXT,
                               created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                               deleted_at     TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_utility_cycle_start ON utility_cycle (property_id, service, effective_from) WHERE deleted_at IS NULL;

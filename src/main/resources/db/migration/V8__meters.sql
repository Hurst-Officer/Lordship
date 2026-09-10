-- ============================================================
-- V8: Meters
-- ============================================================

CREATE TYPE meter_type AS ENUM ('WATER', 'ENERGY');
CREATE TYPE meter_measurement AS ENUM ('GAL', 'KWH', 'CBF');

CREATE TABLE meters (
                            uuid             UUID PRIMARY KEY DEFAULT uuidv7(), -- bool value for monthly/bi-monthly reads?
                            meter_id         UUID NOT NULL, -- ties meter to a lot
                            title            TEXT,
                            description      VARCHAR(255),
                            serial_number    VARCHAR(255) UNIQUE,
                            point_x          DOUBLE PRECISION NOT NULL,
                            point_y          DOUBLE PRECISION NOT NULL,
                            utility_type     meter_type NOT NULL,
                            measurement      meter_measurement NOT NULL,
                            installed_at     DATE,
                            created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                            deleted_at       TIMESTAMPTZ,
                            is_master_meter  BOOL NOT NULL,
                            rollover_max     INTEGER NOT NULL, -- max value displayed on meter
                            meter_multiplier NUMERIC(5,4) NOT NULL DEFAULT 1.0, -- For meters that are 10 GAL/CBF or 100 GAL/CBF; use to calc CBF --> GAL
                            read_due_day     INTEGER NOT NULL DEFAULT 15, -- The day that meters are read; TODO: implement helpers for RDD
                            is_bimonthly     BOOLEAN NOT NULL DEFAULT FALSE, -- If not bimonthly, then monthly
                            FOREIGN KEY (meter_id) REFERENCES lot(uuid),
                            CONSTRAINT uq_meters_uuid_measurement UNIQUE (uuid, measurement)
);

CREATE TABLE meter_relationship (
                            uuid UUID PRIMARY KEY DEFAULT uuidv7(),
                            parent_meter UUID NOT NULL,
                            child_meter UUID,
                            has_unmetered_remainder BOOLEAN NOT NULL DEFAULT FALSE,
                            effective_from DATE NOT NULL DEFAULT CURRENT_DATE,
                            effective_to DATE, -- in case a relationship were to change
                            FOREIGN KEY (parent_meter) REFERENCES meters(uuid),
                            FOREIGN KEY (child_meter) REFERENCES meters(uuid),
                            CONSTRAINT meter_relationship_child_must_differ_from_parent CHECK (child_meter <> parent_meter),
                            CONSTRAINT meter_relationship_remainder_means_no_child_meter CHECK ((child_meter IS NULL) = (has_unmetered_remainder = TRUE))
);

CREATE TABLE meter_reads (
                            uuid UUID PRIMARY KEY DEFAULT uuidv7(),
                            targeted_meter UUID NOT NULL,
                            meter_amount INT NOT NULL,
                            read_at TIMESTAMPTZ NOT NULL,
                            is_estimated BOOLEAN NOT NULL DEFAULT FALSE,
                            rollover_count INT NOT NULL DEFAULT 0, -- may be harder to calculate for pre-existing meters
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            deleted_at TIMESTAMPTZ,
                            FOREIGN KEY (targeted_meter) REFERENCES meters(uuid)
);

-- Used in the MeterBills package
CREATE TABLE meter_billing (
                           uuid             UUID PRIMARY KEY DEFAULT uuidv7(),
                           billed_meter     UUID NOT NULL,
                           billed_amount    NUMERIC(12,2) NOT NULL,   -- charge displayed on a utility bill
                           rate_amount      NUMERIC(10,4) NOT NULL,  -- a calculated rate for water/energy
                           rate_unit        meter_measurement NOT NULL,
                           period_start     DATE NOT NULL,
                           period_end       DATE NOT NULL,
                           created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                           updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                           deleted_at       TIMESTAMPTZ,
                           FOREIGN KEY (billed_meter, rate_unit) REFERENCES meters (uuid, measurement),
                           CONSTRAINT meter_billing_period_end_must_follow_start CHECK (period_end > period_start)
);


-- Enforces one meterId for a given meter
CREATE UNIQUE INDEX uix_meters_lot_active
    ON meters (meter_id)
    WHERE deleted_at IS NULL;


-- For creating/using queries
CREATE INDEX idx_meters_utility_type ON meters(utility_type);
CREATE INDEX idx_meters_measurement ON meters(measurement);

CREATE UNIQUE INDEX uix_meter_relationship_child_active
    ON meter_relationship (child_meter)
    WHERE effective_to IS NULL;


-- Parent and child meters must be of the same utility type
CREATE OR REPLACE FUNCTION check_meter_relationship_utility_match()
   RETURNS TRIGGER AS $$
BEGIN
       IF (SELECT utility_type FROM meters WHERE uuid = NEW.parent_meter)
          <> (SELECT utility_type FROM meters WHERE uuid = NEW.child_meter) THEN
           RAISE EXCEPTION 'Parent and child meters must share the same utility type'
               USING ERRCODE = '23514';
END IF;
RETURN NEW;
END;
   $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_meter_relationship_utility_match
    BEFORE INSERT OR UPDATE ON meter_relationship
                         FOR EACH ROW EXECUTE FUNCTION check_meter_relationship_utility_match();
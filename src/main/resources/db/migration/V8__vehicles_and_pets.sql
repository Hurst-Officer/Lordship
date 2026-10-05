-- ============================================================
-- V8: Vehicles & Pets
-- ============================================================

CREATE TABLE vehicle (
                     uuid          UUID PRIMARY KEY DEFAULT uuidv7(),
                     tenancy_id    UUID NOT NULL,
                     make          VARCHAR(100),
                     model         VARCHAR(100),
                     year          INT,
                     plate_number  VARCHAR(20) NOT NULL,
                     plate_state   VARCHAR(2),
                     color         VARCHAR(50),
                     notes         TEXT,
                     created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                     deleted_at    TIMESTAMPTZ,
                     FOREIGN KEY (tenancy_id)  REFERENCES tenancy(uuid)
);


CREATE INDEX idx_vehicle_tenancy  ON vehicle(tenancy_id)  WHERE deleted_at IS NULL;



-- ── Pet ──────────────────────────────────────────────────────────────────────

CREATE TABLE pet (
                     uuid       UUID PRIMARY KEY DEFAULT uuidv7(),
                     pet_name   VARCHAR(120),
                     pet_type   VARCHAR(120),
                     pet_breed  VARCHAR(120),
                     created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                     deleted_at TIMESTAMPTZ
);
-- ============================================================
-- V1: Enums & Property
-- ============================================================

CREATE TYPE user_type AS ENUM ('AGENT', 'TENANT', 'SYSTEM');
CREATE TYPE operation_type AS ENUM ('INSERT', 'UPDATE', 'DELETE');

-- note: later statute can be looked up using agreement_type + property.state
CREATE TYPE agreement_type AS ENUM (
    'RESIDENTIAL','LAND',
    'TRANSIENT','COMMERCIAL',
    'STORAGE',
    'UTILITY_SERVICE' -- for example: a sub-parcel on your parcel that pays you for a utility
    );

-- shared by instrument and document_template so the vocabulary is defined once
CREATE TYPE instrument_type AS ENUM (
    'LEASE','INCREASE_NOTICE','ASSUMPTION','ADDENDUM','WAIVER', 'PAY_OR_VACATE'
    );

-- ── Global settings ──────────────────────────────────────────────────────────

CREATE TABLE global_settings ( -- singleton
                                 id         INT PRIMARY KEY DEFAULT 1 CONSTRAINT global_settings_must_be_a_singleton CHECK (id = 1),
                                 compliance_email TEXT,

                                 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO global_settings (id) VALUES (1);

-- ── Property ─────────────────────────────────────────────────────────────────

CREATE TABLE property (
                          uuid             UUID PRIMARY KEY DEFAULT uuidv7(),
                          property_code    VARCHAR(255),
                          property_name    TEXT NOT NULL,
                          property_address TEXT NOT NULL,
                          property_city    TEXT,
                          property_state   VARCHAR(2),
                          property_zip     TEXT,
                          purchase_date    DATE,
                          property_zoning  TEXT,
                          property_parcel  TEXT,
                          payable_to       TEXT, -- who to pay
                          remittance_address TEXT, -- where checks are mailed;
                          year_built       INT,
                          property_manager UUID, -- FK to agent added in V3 after agent table exists
                          created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                          deleted_at       TIMESTAMPTZ
);


CREATE TABLE terms_template ( -- note when the property is NULL this is a global default accessible to admins to copy towards properties
                            uuid     UUID PRIMARY KEY DEFAULT uuidv7(),
                            property UUID REFERENCES property(uuid), -- note only an admin can copy this to a property
                            copied_from UUID REFERENCES terms_template(uuid), -- provenance only; may point at a retired set
                            name     TEXT NOT NULL CONSTRAINT terms_template_name_must_not_be_blank CHECK (length(trim(name)) > 0),
                            agreement_type agreement_type NOT NULL, -- do not patch
                            target_rate NUMERIC(12,2) CONSTRAINT terms_template_target_rate_must_not_be_negative CHECK (target_rate >= 0), -- keep zero for global terms
                            asking_rate NUMERIC(12,2) CONSTRAINT terms_template_asking_rate_must_not_be_negative CHECK (asking_rate >= 0),

                            car_fee           NUMERIC(12,2) NOT NULL DEFAULT 65.0 CONSTRAINT terms_template_car_fee_must_not_be_negative CHECK (car_fee >= 0),
                            allowed_cars      INT           NOT NULL DEFAULT 2    CONSTRAINT terms_template_allowed_cars_must_not_be_negative CHECK (allowed_cars >= 0),
                            cars_max          INT           NOT NULL DEFAULT 4    CONSTRAINT terms_template_cars_max_at_least_allowed_cars CHECK (cars_max >= allowed_cars),
                            pet_fee           NUMERIC(12,2) NOT NULL DEFAULT 45.0 CONSTRAINT terms_template_pet_fee_must_not_be_negative CHECK (pet_fee >= 0),
                            allowed_pets      INT           NOT NULL DEFAULT 2    CONSTRAINT terms_template_allowed_pets_must_not_be_negative CHECK (allowed_pets >= 0),

                            payment_due_day   INT           NOT NULL DEFAULT 1    CONSTRAINT terms_template_payment_due_day_must_be_1_to_28 CHECK (payment_due_day BETWEEN 1 AND 28),
                            grace_period_days INT           NOT NULL DEFAULT 7    CONSTRAINT terms_template_grace_period_days_not_negative CHECK (grace_period_days >= 0),

                            rule_violation_fee_method TEXT NOT NULL DEFAULT 'FLAT'
                                CONSTRAINT terms_template_violation_fee_method_must_be_known CHECK (rule_violation_fee_method IN ('NONE','FLAT')),
                            rule_violation_fee_amount NUMERIC(12,2) NOT NULL DEFAULT 65 CONSTRAINT terms_template_violation_fee_amount_not_negative CHECK (rule_violation_fee_amount >= 0),

                            nsf_fee_method    TEXT          NOT NULL DEFAULT 'FLAT'
                                CONSTRAINT terms_template_nsf_fee_method_must_be_known CHECK (nsf_fee_method IN ('NONE','FLAT','BANK_OR_FLAT')), -- BANK_OR_FLAT: either the flat amt or the bank fee if the bank fee is greater
                            nsf_fee_amount    NUMERIC(12,2) NOT NULL DEFAULT 25.0 CONSTRAINT terms_template_nsf_fee_amount_must_not_be_negative CHECK (nsf_fee_amount >= 0),

                            late_fee_method   TEXT          NOT NULL DEFAULT 'FLAT'
                                CONSTRAINT terms_template_late_fee_method_must_be_known CHECK (late_fee_method IN ('NONE','FLAT', 'PERCENT_OF_RENT')),
                            late_fee_amount   NUMERIC(12,2) NOT NULL DEFAULT 65.0 CONSTRAINT terms_template_late_fee_amount_must_not_be_negative CHECK (late_fee_amount >= 0), -- can be a percent OR a flat rate

                            water_method      TEXT          NOT NULL DEFAULT 'NONE'
                                CONSTRAINT terms_template_water_method_must_be_known CHECK (water_method IN ('NONE','FLAT','RUBS','SUBMETERED')),
                            water_flat_amount NUMERIC(12,2) NOT NULL DEFAULT 0 CONSTRAINT terms_template_water_amount_must_not_be_negative CHECK (water_flat_amount >= 0),
                            power_method      TEXT          NOT NULL DEFAULT 'NONE'
                                CONSTRAINT terms_template_power_method_must_be_known CHECK (power_method IN ('NONE','FLAT','RUBS','SUBMETERED')),
                            power_flat_amount NUMERIC(12,2) NOT NULL DEFAULT 0 CONSTRAINT terms_template_power_amount_must_not_be_negative CHECK (power_flat_amount >= 0),
                            sewer_method      TEXT          NOT NULL DEFAULT 'NONE'
                                CONSTRAINT terms_template_sewer_method_must_be_known CHECK (sewer_method IN ('NONE','FLAT','RUBS','SUBMETERED')),
                            sewer_flat_amount NUMERIC(12,2) NOT NULL DEFAULT 0 CONSTRAINT terms_template_sewer_amount_must_not_be_negative CHECK (sewer_flat_amount >= 0),
                            trash_method      TEXT          NOT NULL DEFAULT 'NONE'
                                CONSTRAINT terms_template_trash_method_must_be_known CHECK (trash_method IN ('NONE','FLAT','RUBS')),
                            trash_flat_amount NUMERIC(12,2) NOT NULL DEFAULT 0 CONSTRAINT terms_template_trash_amount_must_not_be_negative CHECK (trash_flat_amount >= 0),

                            note       TEXT,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), -- note: most other tables do not get this row
                            created_by UUID NOT NULL,  -- FK added in V3 after agent table exists
                            deleted_at TIMESTAMPTZ,

                            CONSTRAINT terms_template_late_fee_amount_must_match_method CHECK (
                                CASE WHEN late_fee_method IN ('FLAT', 'PERCENT_OF_RENT')  THEN late_fee_amount > 0
                                     ELSE late_fee_amount = 0 END
                                ),
                            CONSTRAINT terms_template_water_amount_must_match_method CHECK (
                                CASE WHEN water_method = 'FLAT' THEN water_flat_amount > 0
                                     ELSE water_flat_amount = 0 END
                                ),
                            CONSTRAINT terms_template_power_amount_must_match_method CHECK (
                                CASE WHEN power_method = 'FLAT' THEN power_flat_amount > 0
                                     ELSE power_flat_amount = 0 END
                                ),
                            CONSTRAINT terms_template_sewer_amount_must_match_method CHECK (
                                CASE WHEN sewer_method = 'FLAT' THEN sewer_flat_amount > 0
                                     ELSE sewer_flat_amount = 0 END
                                ),
                            CONSTRAINT terms_template_trash_amount_must_match_method CHECK (
                                CASE WHEN trash_method = 'FLAT' THEN trash_flat_amount > 0
                                     ELSE trash_flat_amount = 0 END
                                ),
                            CONSTRAINT terms_template_nsf_amount_must_match_method CHECK (
                                CASE WHEN nsf_fee_method IN ('FLAT','BANK_OR_FLAT')
                                         THEN nsf_fee_amount > 0
                                     ELSE nsf_fee_amount = 0 END
                                ),
                            CONSTRAINT terms_template_violation_amount_must_match_method CHECK (
                                CASE WHEN rule_violation_fee_method = 'FLAT'
                                         THEN rule_violation_fee_amount IS NOT NULL AND rule_violation_fee_amount > 0
                                     ELSE COALESCE(rule_violation_fee_amount, 0) = 0 END
                                )
);


CREATE TABLE property_fee_cap (
                                  uuid           UUID PRIMARY KEY DEFAULT uuidv7(),
                                  property       UUID NOT NULL REFERENCES property(uuid),
                                  agreement_type agreement_type NOT NULL,
                                  fee_type       TEXT NOT NULL
                                      CONSTRAINT property_fee_cap_fee_type_must_be_known CHECK (fee_type IN ('LATE','NSF','PET','CAR','VIOLATION')),

                                  cap_flat       NUMERIC(12,2) CONSTRAINT property_fee_cap_flat_must_not_be_negative CHECK (cap_flat IS NULL OR cap_flat >= 0),
                                  cap_percent_of_rent NUMERIC(5,4)
                                      CONSTRAINT property_fee_cap_percent_must_be_0_to_1 CHECK (cap_percent_of_rent IS NULL OR (cap_percent_of_rent >= 0 AND cap_percent_of_rent <= 1)),

                                  cap_source     TEXT NOT NULL CONSTRAINT property_fee_cap_source_must_not_be_blank CHECK (length(trim(cap_source)) > 0),
                                  note           TEXT,
                                  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  created_by     UUID NOT NULL,  -- FK added in V3 after agent table exists
                                  deleted_at     TIMESTAMPTZ,
                                  CONSTRAINT property_fee_cap_needs_a_flat_or_percent_limit CHECK (cap_flat IS NOT NULL OR cap_percent_of_rent IS NOT NULL)
);

CREATE UNIQUE INDEX property_fee_cap_uq
    ON property_fee_cap (property, agreement_type, fee_type)
    WHERE deleted_at IS NULL;

CREATE TABLE property_fee_waiver (
                                 uuid           UUID PRIMARY KEY DEFAULT uuidv7(),
                                 property       UUID NOT NULL REFERENCES property(uuid),
                                 agreement_type agreement_type NOT NULL,
                                 fee_type       TEXT NOT NULL
                                     CONSTRAINT property_fee_waiver_fee_type_must_be_known CHECK (fee_type IN ('LATE','NSF','PET','CAR','VIOLATION')),
                                 void_above_occupancy_rate NUMERIC(5,4) NOT NULL
                                     CONSTRAINT property_fee_waiver_occupancy_must_be_0_to_1 CHECK (void_above_occupancy_rate >= 0 AND void_above_occupancy_rate <= 1),
                                 reason     TEXT NOT NULL CONSTRAINT property_fee_waiver_reason_must_not_be_blank CHECK (length(trim(reason)) > 0),
                                 note       TEXT,
                                 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                 created_by UUID NOT NULL,  -- FK added in V3 after agent table exists
                                 deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX property_fee_waiver_uq
    ON property_fee_waiver (property, agreement_type, fee_type)
    WHERE deleted_at IS NULL;

-- default standard terms seeded in V10
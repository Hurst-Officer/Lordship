-- ============================================================
-- V2: Person & Property Info
-- ============================================================

-- ── Person ───────────────────────────────────────────────────────────────────

CREATE TABLE person (
                        uuid              UUID PRIMARY KEY DEFAULT uuidv7(),
                        name_full         VARCHAR(120),
                        birthday          DATE,
                        personal_phone    VARCHAR(120),
                        personal_email    VARCHAR(120),
                        mailing_address   TEXT,
                        emergency_contact UUID,
                        social            VARCHAR(72),   -- AES-256 encrypted SSN
                        created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                        deleted_at        TIMESTAMPTZ,
                        FOREIGN KEY (emergency_contact) REFERENCES person(uuid)
);

CREATE INDEX idx_person_name_full ON person(LOWER(name_full))      WHERE deleted_at IS NULL;
CREATE INDEX idx_person_email     ON person(LOWER(personal_email)) WHERE deleted_at IS NULL;


-- ── Property Info ─────────────────────────────────────────────────────────────

CREATE TABLE property_contact (
                                  uuid          UUID PRIMARY KEY DEFAULT uuidv7(),
                                  property_id   UUID NOT NULL,
                                  person_uuid   UUID NOT NULL,
                                  title         VARCHAR(80),  -- what they are to this property: "Plumber", "Sheriff", "On-site helper"
                                  contact_phone VARCHAR(120),
                                  contact_email VARCHAR(120),
                                  description   TEXT,
                                  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  deleted_at    TIMESTAMPTZ,
                                  FOREIGN KEY (property_id) REFERENCES property(uuid),
                                  FOREIGN KEY (person_uuid) REFERENCES person(uuid)
);

CREATE TABLE property_link (
                               uuid        UUID PRIMARY KEY DEFAULT uuidv7(),
                               property_id UUID NOT NULL,
                               title       TEXT NOT NULL,
                               description TEXT,
                               url         TEXT NOT NULL,
                               created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                               deleted_at  TIMESTAMPTZ,
                               FOREIGN KEY (property_id) REFERENCES property(uuid)
);

CREATE INDEX idx_property_contact_property ON property_contact(property_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_property_contact_person   ON property_contact(person_uuid) WHERE deleted_at IS NULL;
CREATE INDEX idx_property_link_property    ON property_link(property_id)    WHERE deleted_at IS NULL;
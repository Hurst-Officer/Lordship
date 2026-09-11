-- ============================================================
-- V9: Documents and Deals
-- ============================================================
-- Changed since last pass, all marked CHANGED:
--   * property_permissible_document      -> property_document_assignment
--   * property_permissible_document_override -> property_document_customization
--   * template_clause gains condition_field / condition_values: a clause whose
--     wording depends on a method is authored once per method, not branched
--     inside the body
--   * instrument_clause keeps body_template alongside body, so a served
--     document can be re-checked against the term it was printed from

-- Every blob, in and out: rendered PDFs, proof of service, signed returns,
-- scanned legacy leases. Not clauses -- clauses live in template_clause.
CREATE TABLE document_file ( -- WIP
                               uuid         UUID PRIMARY KEY DEFAULT uuidv7(),
                               file_name    TEXT NOT NULL,
                               content_type TEXT NOT NULL,
                               byte_size    BIGINT NOT NULL,
                               sha256       BYTEA NOT NULL,
                               storage_key  TEXT NOT NULL,
                               uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                               uploaded_by  UUID NOT NULL REFERENCES agent(uuid) -- SystemPrincipal for anything the renderer produced
);

-- One storage object, one row. Without this a retry or double-submit silently
-- creates a second row pointing at the same blob, and deleting either one
-- orphans the other.
CREATE UNIQUE INDEX document_file_storage_key_uq ON document_file (storage_key);

-- note: the same PDF can legitimately be connected to two different tenancies. Indexed so duplicates can be found
CREATE INDEX document_file_sha256_idx ON document_file (sha256);


-- ── Document templates ───────────────────────────────────────────────────────
-- Global and admin-only, and not coupled to terms_template: a document is the
-- instrument that explains a deal, not part of it. Edits here reach every
-- property the document is assigned to, and every future render. Renders
-- already made are frozen on instrument_section / instrument_clause.

CREATE TABLE document_template (
                                   uuid            UUID PRIMARY KEY DEFAULT uuidv7(),
                                   name            TEXT NOT NULL,
                                   agreement_type  agreement_type NOT NULL, -- must match the term's type at generate
                                   instrument_type instrument_type NOT NULL,
                                   version         INT NOT NULL DEFAULT 1, -- bumped by the service on any clause change; provenance only
                                   note            TEXT,
                                   created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                                   created_by      UUID NOT NULL REFERENCES agent(uuid),
                                   deleted_at      TIMESTAMPTZ,

    -- lets property_document_assignment carry the two enums with a
    -- composite FK, so they cannot drift from the template
                                   CONSTRAINT document_template_kind_uq UNIQUE (uuid, agreement_type, instrument_type)
);


-- One packet is several sub-documents -- Checklist, Tenant Information, Lot
-- Rental Agreement, Rules and Regulations, Vehicle Agreement, Pet Agreement,
-- Septic Addendum -- each signed separately and each with its own line in the
-- agreement's attached-addenda checklist.
CREATE TABLE document_section (
                                  uuid            UUID PRIMARY KEY DEFAULT uuidv7(),
                                  template        UUID NOT NULL REFERENCES document_template(uuid),
                                  ordinal         NUMERIC(10,4) NOT NULL, -- sparse, same reason as clauses
                                  name            TEXT NOT NULL, -- "Septic / Sewer Addendum"
                                  section_key     TEXT, -- stable identity across versions, e.g. SEPTIC_ADDENDUM
                                  signature_block BOOLEAN NOT NULL DEFAULT FALSE, -- signed on its own, not just under the main agreement
                                  listed_as_addendum BOOLEAN NOT NULL DEFAULT FALSE, -- gets a checkbox in the packet's addenda list
                                  required        BOOLEAN NOT NULL DEFAULT FALSE, -- a property cannot drop it
                                  statute_ref     TEXT,
                                  note            TEXT,
                                  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  created_by      UUID NOT NULL REFERENCES agent(uuid),
                                  deleted_at      TIMESTAMPTZ,

                                  CONSTRAINT document_section_template_uq UNIQUE (uuid, template)
);

CREATE INDEX document_section_template_idx ON document_section (template);


-- CHANGED: condition_field / condition_values. A clause whose wording depends on
-- a method is authored once per method and selected at generate, so the body
-- stays editable and can never describe a method the term does not carry.
--
--   condition_field 'nsf_fee_method', values {BANK_OR_FLAT}
--     -> "...the bank's charge or {{nsf_fee_amount}}, whichever is greater."
--   condition_field 'nsf_fee_method', values {FLAT}
--     -> "...a fee of {{nsf_fee_amount}}."
--   NONE selects neither, so no NSF clause is printed.
CREATE TABLE template_clause (
                                 uuid        UUID PRIMARY KEY DEFAULT uuidv7(),
                                 section     UUID NOT NULL REFERENCES document_section(uuid),
                                 ordinal     NUMERIC(10,4) NOT NULL, -- sparse sort key within the section: 12.5 slots between 12 and 13
                                 clause_key  TEXT, -- stable identity across versions, e.g. RENT_AND_FEES
                                 title       TEXT,
                                 body        TEXT, -- tokens only, never a literal amount

                                 condition_field  TEXT,   -- a name from the code-side token registry; null means always include
                                 condition_values TEXT[], -- include when the term's value for that field is in this set

                                 required    BOOLEAN NOT NULL DEFAULT FALSE, -- guards the edit screen; statute_ref is what guards generate
                                 statute_ref TEXT, -- the citation this clause exists to satisfy
                                 note        TEXT,
                                 created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                                 created_by  UUID NOT NULL REFERENCES agent(uuid),
                                 deleted_at  TIMESTAMPTZ
    -- TODO at finalize: condition_field and condition_values are both set or both null
);

CREATE INDEX template_clause_section_idx ON template_clause (section);


-- CHANGED (was property_permissible_document). Which documents a property uses.
-- A reference, not a copy: edits to the template reach the property.
CREATE TABLE property_document_assignment (
                                              uuid              UUID PRIMARY KEY DEFAULT uuidv7(),
                                              property          UUID NOT NULL REFERENCES property(uuid),
                                              document_template UUID NOT NULL REFERENCES document_template(uuid),

    -- Carried so one park cannot end up with two documents answering
    -- to the same kind of deal. Kept honest by the composite FK.
                                              agreement_type    agreement_type NOT NULL,
                                              instrument_type   instrument_type NOT NULL,

                                              note              TEXT,
                                              created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                                              created_by        UUID NOT NULL REFERENCES agent(uuid),
                                              deleted_at        TIMESTAMPTZ,

                                              CONSTRAINT property_document_assignment_kind_fk
                                                  FOREIGN KEY (document_template, agreement_type, instrument_type)
                                                      REFERENCES document_template (uuid, agreement_type, instrument_type)
);

-- "Generate the lease" has to resolve to exactly one document.
CREATE UNIQUE INDEX property_document_assignment_uq
    ON property_document_assignment (property, agreement_type, instrument_type)
    WHERE deleted_at IS NULL;


-- CHANGED (was property_permissible_document_override). What one property
-- changes about a document it was assigned. A park on city sewer drops the whole
-- septic section; a park with a rule of its own adds a clause. Still no REPLACE:
-- a global body is never rewritten locally.
CREATE TABLE property_document_customization (
                                                 uuid        UUID PRIMARY KEY DEFAULT uuidv7(),
                                                 assignment  UUID NOT NULL REFERENCES property_document_assignment(uuid),
                                                 action      TEXT NOT NULL
                                                     CONSTRAINT document_customization_action_must_be_known CHECK (action IN ('EXCLUDE_SECTION','EXCLUDE_CLAUSE','ADD_CLAUSE')),

                                                 section     UUID REFERENCES document_section(uuid), -- EXCLUDE_SECTION, and ADD_CLAUSE says which section receives it
                                                 clause      UUID REFERENCES template_clause(uuid),  -- EXCLUDE_CLAUSE only

                                                 ordinal     NUMERIC(10,4), -- ADD_CLAUSE: shares the coordinate space with template_clause.ordinal
                                                 title       TEXT,
                                                 body        TEXT,

    -- a property-authored clause can be conditional too
                                                 condition_field  TEXT,
                                                 condition_values TEXT[],

                                                 note        TEXT,
                                                 created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                                                 created_by  UUID NOT NULL REFERENCES agent(uuid),
                                                 deleted_at  TIMESTAMPTZ
    -- TODO at finalize, one CHECK per action:
    --   EXCLUDE_SECTION -> section NOT NULL, clause NULL
    --   EXCLUDE_CLAUSE  -> clause NOT NULL, section NULL
    --   ADD_CLAUSE      -> section NOT NULL, clause NULL, body NOT NULL, ordinal NOT NULL
);

CREATE INDEX property_document_customization_idx
    ON property_document_customization (assignment) WHERE deleted_at IS NULL;


CREATE TABLE instrument ( -- WIP
                            uuid           UUID PRIMARY KEY DEFAULT uuidv7(),
                            tenancy        UUID NOT NULL REFERENCES tenancy(uuid),
                            type           instrument_type NOT NULL, -- includes paper that changes nothing: PAY_OR_VACATE, notices

                            status         TEXT NOT NULL DEFAULT 'DRAFT'
                                CONSTRAINT instrument_status_must_be_known CHECK (status IN ('DRAFT','GENERATED','SENT','SERVED','APPROVED','ABANDONED')),

                            serial         TEXT, -- printed on the paper, assigned at GENERATED; typed back in to find the lease
                            amends         UUID, -- composite FK below

    -- This document's own period; null on notices and addenda. on_expiry is
    -- what THIS paper claims happens next, not the system's renewal record.
                            term_start     DATE,
                            term_months    INT CONSTRAINT instrument_term_months_must_be_positive CHECK (term_months > 0), -- 1 for month to month; inherited leases may exceed 12
                            on_expiry      TEXT CONSTRAINT instrument_on_expiry_must_be_known CHECK (on_expiry IN ('MONTH_TO_MONTH','AUTO_RENEW','TERMINATE')),

    -- Where the wording came from. What it SAID is in instrument_clause.
                            template            UUID REFERENCES document_template(uuid),
                            template_version    INT,
                            document_assignment UUID REFERENCES property_document_assignment(uuid), -- CHANGED: renamed

                            generated_at   TIMESTAMPTZ,
                            generated_file UUID REFERENCES document_file(uuid),

                            sent_at        TIMESTAMPTZ,
                            sent_by        UUID REFERENCES agent(uuid),

                            served_on      DATE,
                            service_method TEXT CONSTRAINT instrument_service_method_must_be_known CHECK (service_method IN ('PERSONAL','MAIL','POST_AND_MAIL','EMAIL','OTHER')),
                            served_by      UUID REFERENCES agent(uuid),
                            proof_file     UUID REFERENCES document_file(uuid),

                            returned_on    DATE,
                            returned_file  UUID REFERENCES document_file(uuid),

                            note           TEXT,
                            created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                            created_by     UUID NOT NULL REFERENCES agent(uuid),


                            CONSTRAINT instrument_generated_has_file CHECK (
                                status = 'DRAFT' OR status = 'ABANDONED' OR generated_file IS NOT NULL
                                ),

                            CONSTRAINT instrument_generated_has_timestamp CHECK (
                                generated_file IS NULL OR generated_at IS NOT NULL
                                ),

    -- These three are where the wording came from. What it said is instrument_clause.
                            CONSTRAINT instrument_generated_has_provenance CHECK (
                                generated_file IS NULL
                                    OR (template IS NOT NULL AND template_version IS NOT NULL AND serial IS NOT NULL)
                                ),

                            CONSTRAINT instrument_lease_has_term CHECK (
                                generated_file IS NULL
                                    OR type NOT IN ('LEASE','ASSUMPTION','WAIVER')
                                    OR (term_start IS NOT NULL AND term_months IS NOT NULL)
                                ),

                            CONSTRAINT instrument_served_has_facts CHECK (
                                status <> 'SERVED'
                                    OR (served_on IS NOT NULL AND service_method IS NOT NULL AND served_by IS NOT NULL)
                                ),

                            CONSTRAINT instrument_approved_has_file CHECK (
                                status <> 'APPROVED' OR (returned_on IS NOT NULL AND returned_file IS NOT NULL)
                                -- APPROVED means the signed document is physically back AND an office
                                -- worker has accepted it- (not one or the other)
                                ),

                            CONSTRAINT instrument_dates_ordered CHECK (
                                returned_on IS NULL OR served_on IS NULL OR returned_on >= served_on
                                ),

                            CONSTRAINT instrument_uuid_tenancy_uq UNIQUE (uuid, tenancy),

    -- an addendum or assumption cannot amend another tenancy's lease
                            CONSTRAINT instrument_amends_same_tenancy
                                FOREIGN KEY (amends, tenancy) REFERENCES instrument (uuid, tenancy)
);

CREATE INDEX instrument_tenancy_idx ON instrument (tenancy);

-- Type the serial off the paper, get the lease.
CREATE UNIQUE INDEX instrument_serial_uq
    ON instrument (serial) WHERE serial IS NOT NULL;

-- The office work queue: what paper is out in the field right now.
CREATE INDEX instrument_open_idx
    ON instrument (tenancy)
    WHERE status IN ('GENERATED','SENT','SERVED');


-- ── Rendered packet ──────────────────────────────────────────────────────────
-- The structure and wording that actually produced the PDF. Written once at
-- GENERATED and never edited. What a lease says becomes a query instead of a
-- replay of template@version + customizations, which was never reconstructable.

CREATE TABLE instrument_section (
                                    uuid            UUID PRIMARY KEY DEFAULT uuidv7(),
                                    instrument      UUID NOT NULL REFERENCES instrument(uuid),
                                    ordinal         NUMERIC(10,4) NOT NULL,
                                    name            TEXT NOT NULL,
                                    section_key     TEXT,
                                    signature_block BOOLEAN NOT NULL DEFAULT FALSE,
                                    listed_as_addendum BOOLEAN NOT NULL DEFAULT FALSE,
                                    statute_ref     TEXT,
                                    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

                                    CONSTRAINT instrument_section_instrument_uq UNIQUE (uuid, instrument)
);

CREATE UNIQUE INDEX instrument_section_ordinal_uq ON instrument_section (instrument, ordinal);


-- CHANGED: body_template. Keeping the authored form next to the printed form
-- makes verification mechanical -- re-substitute the term's current values into
-- body_template and compare with body. A mismatch names the clause where the
-- paper and the deal diverged.
CREATE TABLE instrument_clause (
                                   uuid          UUID PRIMARY KEY DEFAULT uuidv7(),
                                   instrument    UUID NOT NULL REFERENCES instrument(uuid),
                                   section       UUID NOT NULL REFERENCES instrument_section(uuid),
                                   ordinal       NUMERIC(10,4) NOT NULL,
                                   clause_key    TEXT, -- carried through so "the rent clause" is findable across leases
                                   title         TEXT,
                                   body          TEXT NOT NULL, -- the words on the page, amounts and all
                                   body_template TEXT NOT NULL, -- the same words with the tokens still in them
                                   statute_ref   TEXT, -- copied down so a generate-time completeness check has something to test
                                   origin        TEXT NOT NULL CONSTRAINT instrument_clause_origin_must_be_known CHECK (origin IN ('TEMPLATE','PROPERTY')),

    -- Deliberately NOT a foreign key: the clause it came from may be
    -- edited, retired or soft-deleted later, and this snapshot has to
    -- outlive that.
                                   source_clause UUID,

                                   created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- no deleted_at, no updated_at: a snapshot is not edited

    -- the section must belong to the same instrument
                                   CONSTRAINT instrument_clause_same_instrument
                                       FOREIGN KEY (section, instrument) REFERENCES instrument_section (uuid, instrument)
);

CREATE UNIQUE INDEX instrument_clause_ordinal_uq ON instrument_clause (section, ordinal);
CREATE INDEX instrument_clause_key_idx ON instrument_clause (clause_key);
-- TODO at finalize: GIN index on to_tsvector('english', body) -- this is the
-- table that makes thousands of leases searchable instead of sitting in PDFs.
-- TODO: a trigger could require a GENERATED instrument to have clauses; the
-- CHECK constraints above cannot reach another table.


-- ── Charge term ──────────────────────────────────────────────────────────────
-- to go into effect two conditions must be met: now() >= valid_at && status = 'ACTIVE'
-- source_uuid is set when the instrument is CREATED, not when the term activates,
-- so the document has a term to substitute from before it renders.

CREATE TABLE tenancy_charge_term (
                                     uuid              UUID PRIMARY KEY DEFAULT uuidv7(),
                                     tenancy           UUID NOT NULL REFERENCES tenancy(uuid),
                                     valid_at          DATE NOT NULL,

                                     agreement_type    agreement_type NOT NULL, -- do not allow editing from patch requests

                                     rate              NUMERIC(12,2) NOT NULL CONSTRAINT charge_term_rate_must_not_be_negative CHECK (rate >= 0), -- COALESCE(lot rate, terms_template rate)
                                     car_fee           NUMERIC(12,2) NOT NULL CONSTRAINT charge_term_car_fee_must_not_be_negative CHECK (car_fee >= 0),
                                     allowed_cars      INT           NOT NULL CONSTRAINT charge_term_allowed_cars_must_not_be_negative CHECK (allowed_cars >= 0),
                                     cars_max          INT           NOT NULL,
                                     pet_fee           NUMERIC(12,2) NOT NULL CONSTRAINT charge_term_pet_fee_must_not_be_negative CHECK (pet_fee >= 0),
                                     allowed_pets      INT           NOT NULL CONSTRAINT charge_term_allowed_pets_must_not_be_negative CHECK (allowed_pets >= 0),

                                     payment_due_day   INT NOT NULL CONSTRAINT charge_term_payment_due_day_must_be_1_to_28 CHECK (payment_due_day BETWEEN 1 AND 28),
                                     grace_period_days INT NOT NULL CONSTRAINT charge_term_grace_period_days_not_negative CHECK (grace_period_days >= 0),

                                     rule_violation_fee_method TEXT NOT NULL
                                         CONSTRAINT charge_term_violation_fee_method_must_be_known CHECK (rule_violation_fee_method IN ('NONE','FLAT')),
                                     rule_violation_fee_amount NUMERIC(12,2) NOT NULL CONSTRAINT charge_term_violation_fee_amount_not_negative CHECK (rule_violation_fee_amount >= 0),

                                     nsf_fee_method    TEXT NOT NULL
                                         CONSTRAINT charge_term_nsf_fee_method_must_be_known CHECK (nsf_fee_method IN ('NONE','FLAT','BANK_OR_FLAT')), -- BANK_OR_FLAT: either the flat amt or the bank fee if the bank fee is greater
                                     nsf_fee_amount    NUMERIC(12,2) NOT NULL CONSTRAINT charge_term_nsf_fee_amount_must_not_be_negative CHECK (nsf_fee_amount >= 0),

                                     late_fee_method   TEXT NOT NULL
                                         CONSTRAINT charge_term_late_fee_method_must_be_known CHECK (late_fee_method IN ('NONE','FLAT', 'PERCENT_OF_RENT')),
                                     late_fee_amount   NUMERIC(12,2) NOT NULL CONSTRAINT charge_term_late_fee_amount_must_not_be_negative CHECK (late_fee_amount >= 0), -- can be a percent OR a flat rate

                                     water_method      TEXT NOT NULL
                                         CONSTRAINT charge_term_water_method_must_be_known CHECK (water_method IN ('NONE','FLAT','RUBS','SUBMETERED')),
                                     water_flat_amount NUMERIC(12,2) NOT NULL CONSTRAINT charge_term_water_amount_must_not_be_negative CHECK (water_flat_amount >= 0),
                                     power_method      TEXT NOT NULL
                                         CONSTRAINT charge_term_power_method_must_be_known CHECK (power_method IN ('NONE','FLAT','RUBS','SUBMETERED')),
                                     power_flat_amount NUMERIC(12,2) NOT NULL CONSTRAINT charge_term_power_amount_must_not_be_negative CHECK (power_flat_amount >= 0),
                                     sewer_method      TEXT NOT NULL
                                         CONSTRAINT charge_term_sewer_method_must_be_known CHECK (sewer_method IN ('NONE','FLAT','RUBS','SUBMETERED')),
                                     sewer_flat_amount NUMERIC(12,2) NOT NULL CONSTRAINT charge_term_sewer_amount_must_not_be_negative CHECK (sewer_flat_amount >= 0),
                                     trash_method      TEXT NOT NULL
                                         CONSTRAINT charge_term_trash_method_must_be_known CHECK (trash_method IN ('NONE','FLAT','RUBS')),
                                     trash_flat_amount NUMERIC(12,2) NOT NULL CONSTRAINT charge_term_trash_amount_must_not_be_negative CHECK (trash_flat_amount >= 0),

                                     security_deposit_method   TEXT NOT NULL
                                         CONSTRAINT charge_term_security_deposit_method_must_be_known CHECK(security_deposit_method IN ('NONE', 'FLAT', 'MULTIPLE_OF_RENT')),
                                     security_deposit_amount   NUMERIC(12,2) NOT NULL CONSTRAINT charge_term_security_deposit_amount_must_not_be_negative CHECK (security_deposit_amount >= 0),
                                        -- note: security_deposit_amount can be a flat amount OR a multiple

                                     status            TEXT NOT NULL DEFAULT 'PROPOSED'
                                         CONSTRAINT charge_term_status_must_be_known CHECK (status IN ('PROPOSED','PENDING','ACTIVE','CANCELLED')),
    -- PROPOSED  - editable, filled in incrementally
    -- PENDING   - submitted- a document is out for signature or service
    -- ACTIVE    - in force from valid_at until a later ACTIVE term supersedes it
    -- CANCELLED - was in force, retracted; excluded from resolution entirely

                                     source            TEXT NOT NULL
                                         CONSTRAINT charge_term_source_must_be_known CHECK (source IN ('LEASE','INCREASE_NOTICE','ASSUMPTION','ADDENDUM','CORRECTION','MIGRATION')),

                                     source_uuid       UUID,     -- the instrument that produced this deal

                                     terms_template    UUID REFERENCES terms_template(uuid), -- which template seeded the values

                                     batch             UUID,     -- groups one bulk run so it can be reviewed or abandoned together

                                     cancelled_at      TIMESTAMPTZ,
                                     cancelled_by      UUID REFERENCES agent(uuid),
                                     cancel_reason     TEXT,
                                     deleted_at        TIMESTAMPTZ, -- soft delete ONLY for charge terms that never generated charges
                                     note              TEXT,
                                     created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                                     created_by        UUID NOT NULL REFERENCES agent(uuid),

                                     CONSTRAINT term_source_same_tenancy -- the instrument must belong to this tenancy
                                         FOREIGN KEY (source_uuid, tenancy) REFERENCES instrument (uuid, tenancy),

                                     CONSTRAINT term_cars_max_at_least_allowed CHECK (
                                         status = 'PROPOSED' OR cars_max >= allowed_cars
                                         ),

                                     CONSTRAINT term_late_fee_amount_matches_method CHECK (
                                         status = 'PROPOSED' OR
                                         CASE
                                             WHEN late_fee_method IN ('FLAT', 'PERCENT_OF_RENT')
                                                 THEN late_fee_amount > 0
                                             ELSE late_fee_amount = 0
                                             END
                                         ),

                                     CONSTRAINT term_rule_violation_amount_matches_method CHECK (
                                         status = 'PROPOSED' OR
                                         CASE WHEN rule_violation_fee_method = 'FLAT' THEN rule_violation_fee_amount > 0
                                              ELSE rule_violation_fee_amount = 0 END
                                         ),

                                     CONSTRAINT term_nsf_amount_matches_method CHECK (
                                         status = 'PROPOSED' OR
                                         CASE WHEN nsf_fee_method IN ('FLAT','BANK_OR_FLAT') THEN nsf_fee_amount > 0
                                              ELSE nsf_fee_amount = 0 END
                                         ),

                                     CONSTRAINT term_water_amount_matches_method CHECK (
                                         status = 'PROPOSED' OR
                                         CASE WHEN water_method = 'FLAT' THEN water_flat_amount > 0
                                              ELSE water_flat_amount = 0 END
                                         ),
                                     CONSTRAINT term_power_amount_matches_method CHECK (
                                         status = 'PROPOSED' OR
                                         CASE WHEN power_method = 'FLAT' THEN power_flat_amount > 0
                                              ELSE power_flat_amount = 0 END
                                         ),
                                     CONSTRAINT term_sewer_amount_matches_method CHECK (
                                         status = 'PROPOSED' OR
                                         CASE WHEN sewer_method = 'FLAT' THEN sewer_flat_amount > 0
                                              ELSE sewer_flat_amount = 0 END
                                         ),
                                     CONSTRAINT term_trash_amount_matches_method CHECK (
                                         status = 'PROPOSED' OR
                                         CASE WHEN trash_method = 'FLAT' THEN trash_flat_amount > 0
                                              ELSE trash_flat_amount = 0 END
                                         ),
                                     CONSTRAINT term_deposit_amount_matches_method CHECK (
                                         status = 'PROPOSED' OR
                                         CASE WHEN security_deposit_method IN ('FLAT', 'MULTIPLE_OF_RENT') THEN security_deposit_amount > 0
                                              ELSE security_deposit_amount = 0 END
                                         ),

                                     CONSTRAINT term_in_force_needs_paper CHECK (
                                         status NOT IN ('ACTIVE','CANCELLED')
                                             OR source = 'MIGRATION'
                                             OR source_uuid IS NOT NULL
                                         ),

                                     CONSTRAINT term_cancel_facts CHECK (
                                         status <> 'CANCELLED'
                                             OR (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL
                                             AND cancel_reason IS NOT NULL)
                                         ),

                                     CONSTRAINT term_cancel_fields_only_when_cancelled CHECK (
                                         status = 'CANCELLED'
                                             OR (cancelled_at IS NULL AND cancelled_by IS NULL
                                             AND cancel_reason IS NULL)
                                         ),

                                     CONSTRAINT term_delete_only_before_force CHECK (
                                         deleted_at IS NULL OR status IN ('PROPOSED','PENDING')
                                         )
);

-- Two terms cannot take effect for the same tenancy on the same date
CREATE UNIQUE INDEX tenancy_charge_term_in_force_uq
    ON tenancy_charge_term (tenancy, valid_at)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;


-- The work queue: terms still being written or out for signature.
CREATE INDEX tenancy_charge_term_open_idx
    ON tenancy_charge_term (tenancy)
    WHERE status IN ('PROPOSED','PENDING') AND deleted_at IS NULL;

-- Answers "what deal did this document produce," for the instrument detail view.
CREATE INDEX tenancy_charge_term_source_idx
    ON tenancy_charge_term (source_uuid)
    WHERE source_uuid IS NOT NULL;

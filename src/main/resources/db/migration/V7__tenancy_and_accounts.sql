-- ============================================================
-- V7: Tenancy
-- ============================================================

CREATE TABLE tenancy (
                         uuid                  UUID PRIMARY KEY DEFAULT uuidv7(),
                         lot_id                UUID NOT NULL,
                         start_date            DATE, -- possession, not the lease term
                         end_date              DATE,
                         anniversary_on        DATE, -- sticky; established by the first lease or first payment
                         anniversary_source    TEXT
                             CONSTRAINT tenancy_anniversary_source_must_be_known CHECK (anniversary_source IN ('FIRST_LEASE','FIRST_PAYMENT','WAIVER','AGREED','MIGRATED')),
                         no_personal_checks    BOOLEAN NOT NULL DEFAULT FALSE,
                         no_partial_payments   BOOLEAN NOT NULL DEFAULT FALSE,
                         accept_payments       BOOLEAN NOT NULL DEFAULT TRUE,
                         exempt_from_late_fees BOOLEAN NOT NULL DEFAULT FALSE,
                         notes                 TEXT,
                         created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
                         deleted_at            TIMESTAMPTZ,
                         -- Move-In (Glade): the steps from "these people will become tenants" to a tenancy that belongs to the
                         -- rent roll. A tenancy opened through Move-In gets move_in_started_at; a tenancy that came another way
                         -- (the data migration) has none and was never moving in. The stage is worked out from these and the
                         -- lease (tenancy MoveInStage), never stored.
                         move_in_started_at    TIMESTAMPTZ,
                         move_in_signing_on    DATE,    -- the day the signing is booked for
                         move_in_signed_on     DATE,    -- the day the lease was actually signed
                         move_in_copy_given    BOOLEAN NOT NULL DEFAULT FALSE, -- the household was given a copy of the signed packet
                         move_in_done_at       TIMESTAMPTZ, -- finished: the tenancy belongs to the rent roll from now on
                         move_in_done_by       UUID REFERENCES agent(uuid),
                         CONSTRAINT tenancy_move_in_done_needs_start CHECK (move_in_done_at IS NULL OR move_in_started_at IS NOT NULL),
                         FOREIGN KEY (lot_id) REFERENCES lot(uuid)
);

-- Not unique: a lot carries two active tenancies while one is being wound up
-- and the next is being set up. Two is the ceiling, enforced by trigger.
CREATE INDEX idx_tenancy_lot_active
    ON tenancy(lot_id) WHERE end_date IS NULL AND deleted_at IS NULL;

-- Two rules, two triggers, because their column scopes differ. Rentability is
-- asked only of a tenancy arriving on a lot; the ceiling is asked of anything
-- that adds to a lot's active count, reopening included.

CREATE OR REPLACE FUNCTION tenancy_lot_must_be_rentable()
    RETURNS TRIGGER AS $$
DECLARE
    rentable BOOLEAN;
    reason   TEXT;
BEGIN
    SELECT l.is_rentable, l.not_rentable_reason
    INTO rentable, reason
    FROM lot l
    WHERE l.uuid = NEW.lot_id;

    IF NOT rentable THEN
        RAISE EXCEPTION 'Lot % cannot take a new tenancy: %',
            NEW.lot_id, COALESCE(reason, 'no reason recorded')
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION tenancy_active_limit()
    RETURNS TRIGGER AS $$
DECLARE
    active INT;
BEGIN
    IF NEW.end_date IS NOT NULL OR NEW.deleted_at IS NOT NULL THEN
        RETURN NEW;
    END IF;

    -- FOR UPDATE serializes concurrent writes on the same lot, so the count below is not a race
    PERFORM 1 FROM lot WHERE uuid = NEW.lot_id FOR UPDATE;

    SELECT count(*) INTO active
    FROM tenancy t
    WHERE t.lot_id = NEW.lot_id
      AND t.uuid <> NEW.uuid
      AND t.end_date IS NULL
      AND t.deleted_at IS NULL;

    IF active >= 2 THEN
        RAISE EXCEPTION 'Lot % already has two active tenancies', NEW.lot_id
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Insert and lot_id moves only. A lot flipping to not-rentable leaves the
-- tenancies already on it alone, and so does correcting one of their dates.
CREATE TRIGGER trg_tenancy_lot_must_be_rentable
    BEFORE INSERT OR UPDATE OF lot_id ON tenancy
    FOR EACH ROW EXECUTE FUNCTION tenancy_lot_must_be_rentable();

-- end_date and deleted_at are here because clearing either one puts a tenancy
-- back among the active, which the create path never sees.
CREATE TRIGGER trg_tenancy_active_limit
    BEFORE INSERT OR UPDATE OF lot_id, end_date, deleted_at ON tenancy
    FOR EACH ROW EXECUTE FUNCTION tenancy_active_limit();

-- ── Tenant ────────────────────────────────────────────────────────────────────

-- One row per person per stay. A tenancy normally has several: the household is
-- every row on it whose end_date is null.
CREATE TABLE tenant (
                        uuid       UUID PRIMARY KEY DEFAULT uuidv7(),
                        tenancy_id UUID NOT NULL REFERENCES tenancy(uuid),
                        person_id  UUID NOT NULL REFERENCES person(uuid),
                        start_date DATE,
                        end_date   DATE,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        deleted_at TIMESTAMPTZ
);


CREATE TABLE occupant (
                          uuid       UUID PRIMARY KEY DEFAULT uuidv7(),
                          tenancy_id UUID NOT NULL REFERENCES tenancy(uuid),
                          person_id  UUID NOT NULL REFERENCES person(uuid),
                          start_date DATE,
                          end_date   DATE,
                          created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                          deleted_at TIMESTAMPTZ
);



-- An interested party has a legal right to be informed about what happens to the tenancy
CREATE TABLE tenancy_interested_party (
                          uuid       UUID PRIMARY KEY DEFAULT uuidv7(),
                          tenancy_id UUID NOT NULL REFERENCES tenancy(uuid),
                          person_id  UUID NOT NULL REFERENCES person(uuid),
                          notification_reason TEXT, -- explain why we are keeping this person notified
                          start_date DATE,
                          end_date   DATE,
                          accept_payments       BOOLEAN NOT NULL DEFAULT FALSE, -- note: by default do not accept payments
                          notes TEXT,
                          created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                          deleted_at TIMESTAMPTZ
);

-- A person is on a tenancy once at a time. Someone who moves out and later moves
-- back gets a second row: the first one carries an end_date, so it is out of the
-- index and the return does not collide with the stay it follows.
CREATE UNIQUE INDEX uq_occupant_active_person
    ON occupant(tenancy_id, person_id) WHERE end_date IS NULL AND deleted_at IS NULL;

-- Serves "who lives here", which is the question this table exists to answer --
-- the tenant table answers who is on the lease, which is not the same set.
CREATE INDEX idx_occupant_tenancy_active
    ON occupant(tenancy_id) WHERE end_date IS NULL AND deleted_at IS NULL;

CREATE INDEX idx_occupant_person ON occupant(person_id) WHERE deleted_at IS NULL;

-- A person is on a tenancy once at a time. Someone who moves out and later moves
-- back gets a second row: the first one carries an end_date, so it is out of the
-- index and the return does not collide with the stay it follows.
CREATE UNIQUE INDEX uq_tenant_active_person
    ON tenant(tenancy_id, person_id) WHERE end_date IS NULL AND deleted_at IS NULL;

-- Serves "who lives here", the question the table exists to answer.
CREATE INDEX idx_tenant_tenancy_active
    ON tenant(tenancy_id) WHERE end_date IS NULL AND deleted_at IS NULL;

-- Every stay a person has had, for the tenant view.
CREATE INDEX idx_tenant_person ON tenant(person_id) WHERE deleted_at IS NULL;

-- A person has one active interest in a tenancy at a time. Someone whose
-- interest ends and later resumes gets a second row: the first one carries an
-- end_date, so it is out of the index and the return does not collide with it.
CREATE UNIQUE INDEX uq_interested_party_active_person
    ON tenancy_interested_party(tenancy_id, person_id) WHERE end_date IS NULL AND deleted_at IS NULL;

-- Serves "who currently has an interest in this tenancy" (findActiveByTenancy).
CREATE INDEX idx_interested_party_tenancy_active
    ON tenancy_interested_party(tenancy_id) WHERE end_date IS NULL AND deleted_at IS NULL;

-- Every stay a person has had an interest in, for findByPerson.
CREATE INDEX idx_interested_party_person ON tenancy_interested_party(person_id) WHERE deleted_at IS NULL;

-- ── Move-In checks ─────────────────────────────────────────────────────────────

-- The office's list for a move-in's signed lease (Glade's Office review): one row for each item of one move-in, and
-- the requests the office adds by hand. The fixed items are named in Java (tenancy MoveInCheckItem). A fixed item has
-- at most one live row for a move-in, written the first time someone decides the item or the page check reads a
-- scan; an item with no row is PENDING. A list each property edits for itself comes later (move_in_checklist_item).
-- state is the decision that counts: PASSED, WAIVED and NOT_NEEDED settle an item, and Finish is refused while an
-- item that applies is not settled. machine_state and machine_found are what the page check read from the signed
-- scan (instruments SignedScans), kept beside a person's decision so the office sees both. The page check decides
-- PAGES_IN_ORDER by itself; a machine reading never passes an item a person confirms (a signature).
-- decided_by is the agent who set state. A state the page check set has decided_at and no decided_by.
-- A request (item REQUEST) has the office's question, and the park's answer once the park answers.
CREATE TABLE move_in_check (
                               uuid          UUID PRIMARY KEY DEFAULT uuidv7(),
                               tenancy_id    UUID NOT NULL REFERENCES tenancy(uuid),
                               item          TEXT NOT NULL
                                   CONSTRAINT move_in_check_item_must_be_known CHECK (item IN ('TENANTS_SIGNED', 'MANAGER_SIGNED',
                                       'PAGES_IN_ORDER', 'TIF', 'ASSISTANCE_ANIMAL', 'FIRST_RENT', 'DEPOSIT', 'NOTHING_OWED',
                                       'PAYMENT_DETAILS', 'REQUEST')),
                               state         TEXT NOT NULL DEFAULT 'PENDING'
                                   CONSTRAINT move_in_check_state_must_be_known CHECK (state IN ('PENDING', 'PASSED', 'FAILED',
                                       'UNSURE', 'WAIVED', 'NOT_NEEDED')),
                               note          TEXT,
                               decided_by    UUID REFERENCES agent(uuid),
                               decided_at    TIMESTAMPTZ,
                               machine_state TEXT
                                   CONSTRAINT move_in_check_machine_state_must_be_known CHECK (machine_state IN ('PASSED', 'FAILED', 'UNSURE')),
                               machine_found TEXT,
                               machine_at    TIMESTAMPTZ,
                               question      TEXT,
                               answer        TEXT,
                               answered_by   UUID REFERENCES agent(uuid),
                               answered_at   TIMESTAMPTZ,
                               created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                               created_by    UUID NOT NULL REFERENCES agent(uuid),
                               deleted_at    TIMESTAMPTZ,
                               CONSTRAINT move_in_check_request_has_question CHECK ((item = 'REQUEST') = (question IS NOT NULL)),
                               CONSTRAINT move_in_check_answer_only_on_request CHECK (item = 'REQUEST' OR answer IS NULL)
);

-- One live row for each fixed item of a move-in
CREATE UNIQUE INDEX move_in_check_item_uq
    ON move_in_check (tenancy_id, item) WHERE item <> 'REQUEST' AND deleted_at IS NULL;

CREATE INDEX move_in_check_tenancy_idx ON move_in_check (tenancy_id) WHERE deleted_at IS NULL;

-- ── Account ───────────────────────────────────────────────────────────────────

CREATE TABLE account (
                         uuid            UUID PRIMARY KEY DEFAULT uuidv7(),
                         tenancy_id      UUID NOT NULL,
                         account_status  VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
                         balance_cached  NUMERIC(10,2) NOT NULL DEFAULT 0.00,
                         autopay_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                         notes           TEXT,
                         created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                         deleted_at      TIMESTAMPTZ,
                         FOREIGN KEY (tenancy_id) REFERENCES tenancy(uuid)
);

CREATE UNIQUE INDEX uq_account_tenancy_active
    ON account(tenancy_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_account_tenancy_id ON account(tenancy_id) WHERE deleted_at IS NULL;


-- ── Transaction ───────────────────────────────────────────────────────────────

CREATE TABLE transaction ( --TODO:  needs nullable field: a uuid for the batch billing process
                             uuid           UUID PRIMARY KEY DEFAULT uuidv7(),
                             account_id     UUID NOT NULL REFERENCES account(uuid),
                             type           VARCHAR(50) NOT NULL,
                             amount         NUMERIC(14,2) NOT NULL,
                             description    TEXT,
                             billing_period DATE NOT NULL,
                             posted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                             deleted_at     TIMESTAMPTZ,
                             CONSTRAINT transaction_amount_nonzero_negative_only_if_adjustment
                                 CHECK (amount <> 0 AND (amount > 0 OR type = 'BALANCE_ADJUSTMENT'))
);

CREATE INDEX idx_transaction_account_id     ON transaction(account_id);
CREATE INDEX idx_transaction_billing_period ON transaction(billing_period);

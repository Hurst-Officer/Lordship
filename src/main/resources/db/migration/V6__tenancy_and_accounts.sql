-- ============================================================
-- V6: Tenancy
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
                         created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
                         deleted_at            TIMESTAMPTZ,
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
                        tenancy_id UUID NOT NULL,
                        person_id  UUID NOT NULL,
                        start_date DATE,
                        end_date   DATE,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        deleted_at TIMESTAMPTZ,
                        FOREIGN KEY (person_id)  REFERENCES person(uuid),
                        FOREIGN KEY (tenancy_id) REFERENCES tenancy(uuid)
);

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

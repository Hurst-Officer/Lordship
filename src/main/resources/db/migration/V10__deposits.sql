-- ============================================================
-- V10: Security deposits
-- ============================================================
-- Money held on behalf of a tenant, not revenue. Deliberately outside
-- account/transaction: a deposit must never move balance_cached, or an
-- invoice shows the tenant paid up when they are not.

CREATE TABLE security_deposit (
                                  uuid            UUID PRIMARY KEY DEFAULT uuidv7(),
                                  tenancy         UUID NOT NULL REFERENCES tenancy(uuid),
                                  instrument      UUID, -- composite FK below; null on MIGRATION
                                  source          TEXT NOT NULL
                                      CONSTRAINT security_deposit_source_must_be_known
                                          CHECK (source IN ('LEASE','ASSUMPTION','MIGRATION')),

                                  amount          NUMERIC(14,2) NOT NULL
                                      CONSTRAINT security_deposit_amount_must_be_positive CHECK (amount > 0),
                                  collected_on    DATE, -- often unknown on an inherited deposit

                                  amount_refunded NUMERIC(14,2), -- null until disposition; 0 means we kept all of it
                                  refunded_on     DATE,

                                  description     TEXT, -- tenant-facing: "Tony and Linda both paid half"
                                  note            TEXT, -- agent eyes only: "refunded with check #3120"

                                  created_by      UUID NOT NULL REFERENCES agent(uuid),
                                  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  deleted_at      TIMESTAMPTZ,

    -- Both halves of a disposition or neither. A date with no figure cannot
    -- say what was returned; a figure with no date cannot say when.
                                  CONSTRAINT security_deposit_refund_facts_move_together CHECK (
                                      (amount_refunded IS NULL) = (refunded_on IS NULL)
                                      ),

                                  CONSTRAINT security_deposit_refund_must_not_exceed_amount CHECK (
                                      amount_refunded IS NULL
                                          OR (amount_refunded >= 0 AND amount_refunded <= amount)
                                      ),

                                  CONSTRAINT security_deposit_dates_ordered CHECK (
                                      refunded_on IS NULL OR collected_on IS NULL OR refunded_on >= collected_on
                                      ),

    -- A deposit cannot hang off another tenancy's paper. Leans on
    -- instrument_uuid_tenancy_uq, the same way instrument.amends does.
                                  CONSTRAINT security_deposit_instrument_same_tenancy
                                      FOREIGN KEY (instrument, tenancy) REFERENCES instrument (uuid, tenancy)
);

CREATE INDEX security_deposit_tenancy_idx
    ON security_deposit (tenancy) WHERE deleted_at IS NULL;

-- What we are still holding: the balance-sheet question, and the only query
-- here that scans rather than looks up.
CREATE INDEX security_deposit_held_idx
    ON security_deposit (tenancy)
    WHERE refunded_on IS NULL AND deleted_at IS NULL;
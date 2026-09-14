-- ============================================================
-- V10: Security deposits
-- ============================================================
-- Money held on behalf of a tenant, not revenue. Deliberately outside
-- account/transaction: a deposit must never move balance_cached, or an
-- invoice shows the tenant paid up when they are not.
--
-- What happened to the money is NOT here. A refund cheque routinely carries
-- more than the deposit, because an account credit from an overpayment rides
-- along on the same cheque -- so it belongs to the tenant, not to this row.
-- settled_on says the liability ended; a later tenant_refund says how.

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

                                  settled_on      DATE, -- null while we still hold it

                                  description     TEXT, -- tenant-facing: "Tony and Linda both paid half"
                                  note            TEXT, -- agent eyes only: "refunded with check #3120"

                                  created_by      UUID NOT NULL REFERENCES agent(uuid),
                                  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  deleted_at      TIMESTAMPTZ,

                                  CONSTRAINT security_deposit_dates_ordered CHECK (
                                      settled_on IS NULL OR collected_on IS NULL OR settled_on >= collected_on
                                      ),

    -- A deposit cannot hang off another tenancy's paper. Leans on
    -- instrument_uuid_tenancy_uq, the same way instrument.amends does.
                                  CONSTRAINT security_deposit_instrument_same_tenancy
                                      FOREIGN KEY (instrument, tenancy) REFERENCES instrument (uuid, tenancy)
);

CREATE INDEX security_deposit_tenancy_idx
    ON security_deposit (tenancy) WHERE deleted_at IS NULL;

-- What we are still holding: the rent roll figure, and the aging report
-- that says which ones are overdue.
CREATE INDEX security_deposit_held_idx
    ON security_deposit (tenancy)
    WHERE settled_on IS NULL AND deleted_at IS NULL;
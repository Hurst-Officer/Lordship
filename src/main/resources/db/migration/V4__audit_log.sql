-- ============================================================
-- V4: Audit Log
-- ============================================================

CREATE TABLE audit_log (
                           uuid           UUID PRIMARY KEY DEFAULT uuidv7(),
                           correlation_id UUID NOT NULL,
                           user_id        UUID,
                           user_type      user_type NOT NULL,
                           ip_address     VARCHAR(45),
                           table_name     VARCHAR(60) NOT NULL,
                           record_id      VARCHAR(60) NOT NULL,
                           operation      operation_type NOT NULL,
                           value_before   TEXT,
                           value_after    TEXT,
                           changed_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_record      ON audit_log(table_name, record_id);
CREATE INDEX idx_audit_user        ON audit_log(user_id);
-- the rows one save wrote, like every lot of a bulk edit ("with 9 other lots")
CREATE INDEX idx_audit_correlation ON audit_log(correlation_id);

-- ============================================================
-- V3: Agent & Access Control
-- ============================================================

CREATE TABLE agent (
                       uuid           UUID PRIMARY KEY DEFAULT uuidv7(),
                       person_id      UUID NOT NULL,
                       work_phone     VARCHAR(20),
                       work_email     VARCHAR(120),
                       agent_password VARCHAR(255),
                       tokens_valid_from TIMESTAMPTZ, -- null until a password change or revoke ends this agent's sessions
                       created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                       deleted_at     TIMESTAMPTZ,
                       FOREIGN KEY (person_id) REFERENCES person(uuid)
);

CREATE INDEX idx_agent_person_id ON agent(person_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_agent_email_active ON agent(lower(work_email)) WHERE deleted_at IS NULL;

CREATE TABLE agent_login_event (
                                   uuid           UUID PRIMARY KEY DEFAULT uuidv7(),
                                   agent_id       UUID NOT NULL,
                                   occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                                   ip_address     VARCHAR(45),
                                   browser_client TEXT,
                                   browserOs      TEXT,
                                   outcome        SMALLINT NOT NULL,
                                   FOREIGN KEY (agent_id) REFERENCES agent(uuid)
);

CREATE INDEX idx_login_event_agent ON agent_login_event(agent_id, occurred_at DESC);

CREATE TABLE agent_role (
                            uuid             UUID PRIMARY KEY DEFAULT uuidv7(),
                            role_name        VARCHAR(60) NOT NULL,
                            role_description TEXT,
                            created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                            deleted_at       TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_agent_role_name_active
    ON agent_role(role_name) WHERE deleted_at IS NULL;

CREATE TABLE permission ( -- note permissions are seeded and NOT editable (hence no created_at or deleted_at timestamps
                            uuid            UUID PRIMARY KEY DEFAULT uuidv7(),
                            permission_name VARCHAR(60) NOT NULL UNIQUE
);

CREATE TABLE role_permission (  -- where a permission is granted to a role
                                 uuid          UUID PRIMARY KEY DEFAULT uuidv7(),
                                 role_id       UUID NOT NULL,
                                 permission_id UUID NOT NULL,
                                 created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                                 deleted_at    TIMESTAMPTZ,
                                 FOREIGN KEY (role_id)       REFERENCES agent_role(uuid),
                                 FOREIGN KEY (permission_id) REFERENCES permission(uuid)
);

CREATE UNIQUE INDEX uq_role_permission_active
    ON role_permission(role_id, permission_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_role_permission_role_id ON role_permission(role_id) WHERE deleted_at IS NULL;

CREATE TABLE granted_role (
                              uuid       UUID PRIMARY KEY DEFAULT uuidv7(),
                              agent_id   UUID NOT NULL,
                              role_id    UUID NOT NULL,
                              granted_by UUID NOT NULL,
                              revoked_by UUID,
                              created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                              deleted_at TIMESTAMPTZ,
                              FOREIGN KEY (agent_id)   REFERENCES agent(uuid),
                              FOREIGN KEY (role_id)    REFERENCES agent_role(uuid),
                              FOREIGN KEY (granted_by) REFERENCES agent(uuid),
                              FOREIGN KEY (revoked_by) REFERENCES agent(uuid)
);

CREATE UNIQUE INDEX uq_granted_role_active
    ON granted_role(agent_id, role_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_granted_role_agent_id ON granted_role(agent_id) WHERE deleted_at IS NULL;

CREATE TABLE denied_permission (
                                   uuid              UUID PRIMARY KEY DEFAULT uuidv7(),
                                   agent_id          UUID NOT NULL,
                                   permission_id     UUID NOT NULL,
                                   denied_by         UUID NOT NULL,
                                   denial_removed_by UUID,
                                   created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                                   deleted_at        TIMESTAMPTZ,
                                   FOREIGN KEY (agent_id)          REFERENCES agent(uuid),
                                   FOREIGN KEY (permission_id)     REFERENCES permission(uuid),
                                   FOREIGN KEY (denied_by)         REFERENCES agent(uuid),
                                   FOREIGN KEY (denial_removed_by) REFERENCES agent(uuid)
);

CREATE UNIQUE INDEX uq_denied_permission_active
    ON denied_permission(agent_id, permission_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_denied_permission_agent_id ON denied_permission(agent_id) WHERE deleted_at IS NULL;

CREATE TABLE agent_property_assignment (
                                           uuid        UUID PRIMARY KEY DEFAULT uuidv7(),
                                           agent_id    UUID NOT NULL,
                                           property_id UUID NOT NULL,
                                           assigned_by UUID NOT NULL,
                                           assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                                           removed_at  TIMESTAMPTZ,
                                           FOREIGN KEY (agent_id)    REFERENCES agent(uuid),
                                           FOREIGN KEY (property_id) REFERENCES property(uuid),
                                           FOREIGN KEY (assigned_by) REFERENCES agent(uuid)
);

CREATE INDEX idx_assignment_agent    ON agent_property_assignment(agent_id)    WHERE removed_at IS NULL;
CREATE INDEX idx_assignment_property ON agent_property_assignment(property_id) WHERE removed_at IS NULL;
CREATE UNIQUE INDEX uq_active_assignment
    ON agent_property_assignment(agent_id, property_id) WHERE removed_at IS NULL;


-- ── System principal ─────────────────────────────────────────────────────────
-- Attribution target for machine-authored rows (seeds, billing runs, generated
-- documents). Not a login: work_email and agent_password are both NULL, so the
-- email lookup in the login path can never reach it.
-- Fixed UUIDs so migrations can reference it as a literal.

INSERT INTO person (uuid, name_full)
VALUES ('00000000-0000-7000-8000-000000000001', 'System');

INSERT INTO agent (uuid, person_id, work_phone, work_email, agent_password)
VALUES ('00000000-0000-7000-8000-000000000002',
        '00000000-0000-7000-8000-000000000001',
        NULL, NULL, NULL);


-- property_manager FK deferred from V1, now that agent exists
ALTER TABLE property
    ADD CONSTRAINT fk_property_manager FOREIGN KEY (property_manager) REFERENCES agent(uuid);
ALTER TABLE property_fee_cap
    ADD CONSTRAINT fk_agent_editor FOREIGN KEY (created_by) REFERENCES agent(uuid);

ALTER TABLE terms_template
    ADD CONSTRAINT fk_agent_edits_terms FOREIGN KEY (created_by) REFERENCES agent(uuid);
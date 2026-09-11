-- ============================================================
-- V12: All seed data — permissions, standard terms, roles, and role grants.
-- ============================================================

INSERT INTO terms_template (property, created_by, name, agreement_type, target_rate, car_fee, allowed_cars, cars_max, allowed_pets, pet_fee, rule_violation_fee_method, rule_violation_fee_amount, nsf_fee_method, nsf_fee_amount, security_deposit_method, security_deposit_amount)
VALUES (NULL, '00000000-0000-7000-8000-000000000002', 'Standard Manufactured Home Lot Terms', 'LAND', 0, 45, 2, 4, 2, 0, 'FLAT', 65, 'FLAT', 35, 'NONE', 0),
       (NULL, '00000000-0000-7000-8000-000000000002', 'Standard Residential Terms',           'RESIDENTIAL', 0, 45, 2, 4, 2, 45, 'FLAT', 65, 'FLAT', 35, 'MULTIPLE_OF_RENT', 1.00),
       (NULL, '00000000-0000-7000-8000-000000000002', 'Standard Storage Terms',               'STORAGE', 0, 0, 0, 0, 0, 0, 'NONE', 0, 'FLAT', 25, 'NONE', 0);

-- ── Permissions ───────────────────────────────────────────────────────────────

INSERT INTO permission (uuid, permission_name) VALUES
    -- Agents
    (uuidv7(), 'agents:view'),
    (uuidv7(), 'agents:edit'),
    (uuidv7(), 'agents:reset_passwords'),
    (uuidv7(), 'agents:view_own'), -- aspirational
    (uuidv7(), 'agents:edit_own'), -- aspirational
    (uuidv7(), 'agents:create'),
    (uuidv7(), 'agents:delete'),

    -- Permissions
    (uuidv7(), 'permissions:view'),
    (uuidv7(), 'permissions:assign_to_role'),
    (uuidv7(), 'permissions:deny'),

    (uuidv7(), 'role_permissions:delete'),
    (uuidv7(), 'role_permissions:view'),
    (uuidv7(), 'role_permissions:edit'),

    -- Roles
    (uuidv7(), 'agent_roles:view'),
    (uuidv7(), 'agent_roles:edit'),
    (uuidv7(), 'agent_roles:create'),
    (uuidv7(), 'agent_roles:delete'),

    -- Pets
    (uuidv7(), 'pets:view'),
    (uuidv7(), 'pets:edit'),
    (uuidv7(), 'pets:create'),
    (uuidv7(), 'pets:delete'),

    -- Properties
    (uuidv7(), 'properties:view'),
    (uuidv7(), 'properties:edit'),
    (uuidv7(), 'properties:create'),
    (uuidv7(), 'properties:delete'),

    -- Property assignments
    (uuidv7(), 'assignments:assign-all'),
    (uuidv7(), 'assignments:view'),
    (uuidv7(), 'assignments:assign'),
    (uuidv7(), 'assignments:remove'),

    -- Persons
    (uuidv7(), 'persons_ssn:view'),
    (uuidv7(), 'persons_ssn:edit'),
    (uuidv7(), 'persons:view'),
    (uuidv7(), 'persons:edit'),
    (uuidv7(), 'persons:view_own'),
    (uuidv7(), 'persons:edit_own'), -- aspirational
    (uuidv7(), 'persons:create'),
    (uuidv7(), 'persons:delete'),

    -- Audit
    (uuidv7(), 'audit:view'),
    (uuidv7(), 'audit:view_own'), -- aspirational

    -- Tenancy
    (uuidv7(), 'tenancy:view'),
    (uuidv7(), 'tenancy:edit'),
    (uuidv7(), 'tenancy:create'),
    (uuidv7(), 'tenancy:delete'),

    -- Tenants
    (uuidv7(), 'tenants:create'),
    (uuidv7(), 'tenants:view'),
    (uuidv7(), 'tenants:edit'),
    (uuidv7(), 'tenants:delete'),

    -- Lots
    (uuidv7(), 'lots:view'),
    (uuidv7(), 'lots:edit'),
    (uuidv7(), 'lots:set_rates'),
    (uuidv7(), 'lots:create'),
    (uuidv7(), 'lots:delete'),

    -- Accounts
    (uuidv7(), 'accounts:view'),
    (uuidv7(), 'accounts:edit'),

    -- Transactions
    (uuidv7(), 'transactions:view'),
    (uuidv7(), 'transactions:edit'),

    -- Vehicles
    (uuidv7(), 'vehicles:view'),
    (uuidv7(), 'vehicles:edit'),
    (uuidv7(), 'vehicles:create'),
    (uuidv7(), 'vehicles:delete'),

    -- Meters
    (uuidv7(), 'meters:view'),
    (uuidv7(), 'meters:edit'),
    (uuidv7(), 'meters:create'),
    (uuidv7(), 'meters:delete'),

    -- Meter Billing
    (uuidv7(), 'meterbills:view'),
    (uuidv7(), 'meterbills:edit'),
    (uuidv7(), 'meterbills:create'),
    (uuidv7(), 'meterbills:delete'),

    -- standard term
    (uuidv7(), 'terms_template:manage_global'),
    (uuidv7(), 'terms_template:view'),
    (uuidv7(), 'terms_template:edit'),
    (uuidv7(), 'terms_template:create'),
    (uuidv7(), 'terms_template:delete'),

    -- charge term
    (uuidv7(), 'tenancy_term:create_migrations'),
    (uuidv7(), 'tenancy_term:view'),
    (uuidv7(), 'tenancy_term:edit'),
    (uuidv7(), 'tenancy_term:create'),
    (uuidv7(), 'tenancy_term:delete'),
    (uuidv7(), 'tenancy_term:activate'),
    (uuidv7(), 'tenancy_term:cancel'),

    -- property document
    (uuidv7(), 'property_document:view'),
    (uuidv7(), 'property_document:assign'),
    (uuidv7(), 'property_document:unassign'),

    -- documents
    (uuidv7(), 'document_template:view'),
    (uuidv7(), 'document_template:create'),
    (uuidv7(), 'document_template:edit'),
    (uuidv7(), 'document_template:delete'),

    -- homes
    (uuidv7(), 'homes:view'),
    (uuidv7(), 'homes:create'),
    (uuidv7(), 'homes:edit'),
    (uuidv7(), 'homes:delete')
;

-- ── Roles ─────────────────────────────────────────────────────────────────────

INSERT INTO agent_role (uuid, role_name) VALUES
    (uuidv7(), 'Admin'),
    (uuidv7(), 'Office Staff'),
    (uuidv7(), 'Unassigned'),
    (uuidv7(), 'Property Manager');


-- ── Role → Permission grants ──────────────────────────────────────────────────

-- Admin gets all permissions
INSERT INTO role_permission (uuid, role_id, permission_id)
SELECT uuidv7(), r.uuid, p.uuid
FROM agent_role r, permission p
WHERE r.role_name = 'Admin';

-- Office Staff
INSERT INTO role_permission (uuid, role_id, permission_id)
SELECT uuidv7(), r.uuid, p.uuid
FROM agent_role r, permission p
WHERE r.role_name = 'Office Staff'
AND p.permission_name IN (
    'agents:view',
    'agents:view_own',
    'agents:edit_own',
    'permissions:view',
    'role_permissions:view',
    'persons_ssn:view',
    'persons:view',
    'persons:edit',
    'persons:create',
    'persons:view_own',
    'persons:edit_own',
    'pets:view',
    'pets:edit',
    'pets:create',
    'pets:delete',
    'properties:view',
    'assignments:view',
    'assignments:assign-all',
    'audit:view',
    'audit:view_own',
    'accounts:view',
    'accounts:edit',
    'transactions:view',
    'transactions:edit',
    'tenancy:create',
    'tenancy:view',
    'tenancy:edit',
    'tenants:create',
    'tenants:view',
    'tenants:edit',
    'terms_template:view',
    'tenancy_term:view',
    'tenancy_term:edit',
    'tenancy_term:create',
    'tenancy_term:delete',
    'tenancy_term:activate',
    'tenancy_term:cancel',
    'vehicles:view',
    'vehicles:edit',
    'vehicles:create',
    'vehicles:delete',
    'meters:view',
    'meters:edit',
    'meterbills:view',
    'meterbills:edit',
    'document_template:view',
    'property_document:view',
    'homes:view',
    'homes:create',
    'homes:edit'
);

-- Property Manager
INSERT INTO role_permission (uuid, role_id, permission_id)
SELECT uuidv7(), r.uuid, p.uuid
FROM agent_role r, permission p
WHERE r.role_name = 'Property Manager'
AND p.permission_name IN (
    'agents:view_own',
    'agents:edit_own',
    'persons:view',
    'persons:edit',
    'persons:create',
    'persons:view_own',
    'persons:edit_own',
    'pets:view',
    'pets:edit',
    'pets:create',
    'pets:delete',
    'properties:view',
    'assignments:view',
    'audit:view',
    'audit:view_own',
    'accounts:view',
    'transactions:view',
    'tenancy:view',
    'tenancy:edit',
    'tenants:view',
    'tenants:create',
    'tenants:edit',
    'tenancy_term:view',
    'vehicles:view',
    'meters:view',
    'meterbills:view',
    'meterbills:edit',
    'homes:view'
);

-- ============================================================
-- V13: All seed data — permissions, standard terms, roles, and role grants.
-- ============================================================

INSERT INTO terms_template (property, created_by, name, agreement_type, target_rate, car_fee, allowed_cars, cars_max, allowed_pets, pet_fee, rule_violation_fee_method, rule_violation_fee_amount, nsf_fee_method, nsf_fee_amount, security_deposit_method, security_deposit_amount)
VALUES (NULL, '00000000-0000-7000-8000-000000000002', 'Standard Manufactured Home Lot Terms', 'LAND', 0, 45, 2, 4, 2, 0, 'FLAT', 65, 'FLAT', 35, 'NONE', 0),
       (NULL, '00000000-0000-7000-8000-000000000002', 'Standard Residential Terms',           'RESIDENTIAL', 0, 45, 2, 4, 2, 45, 'FLAT', 65, 'FLAT', 35, 'MULTIPLE_OF_RENT', 1.00),
       (NULL, '00000000-0000-7000-8000-000000000002', 'Standard Storage Terms',               'STORAGE', 0, 0, 0, 0, 0, 0, 'NONE', 0, 'FLAT', 25, 'NONE', 0);

-- ── Permissions ───────────────────────────────────────────────────────────────

-- Every permission code. The grouping section below gives each one a group.
CREATE TEMP TABLE permission_seed (
    seed_order      SERIAL,
    permission_name VARCHAR(60) NOT NULL
) ON COMMIT DROP;

INSERT INTO permission_seed (permission_name) VALUES
                                                   -- Agents
                                                   ('agents:view'),
                                                   ('agents:edit'),
                                                   ('agents:reset_passwords'),
                                                   ('agents:end_sessions'),
                                                   ('agents:view_own'), -- aspirational
                                                   ('agents:edit_own'), -- aspirational
                                                   ('agents:create'),
                                                   ('agents:delete'),

                                                   -- Permissions
                                                   ('permissions:view'),
                                                   ('permissions:assign_to_role'),
                                                   ('permissions:deny'),

                                                   ('role_permissions:delete'),
                                                   ('role_permissions:view'),
                                                   ('role_permissions:edit'),

                                                   -- Roles
                                                   ('agent_roles:view'),
                                                   ('agent_roles:edit'),
                                                   ('agent_roles:create'),
                                                   ('agent_roles:delete'),

                                                   -- Pets
                                                   ('pets:view'),
                                                   ('pets:edit'),
                                                   ('pets:create'),
                                                   ('pets:delete'),

                                                   -- Properties
                                                   ('properties:view'),
                                                   ('properties:edit'),
                                                   ('properties:create'),
                                                   ('properties:delete'),

                                                   -- Site maps
                                                   ('sitemaps:edit'),

                                                   -- Property assignments
                                                   ('assignments:assign-all'),
                                                   ('assignments:view'),
                                                   ('assignments:assign'),
                                                   ('assignments:remove'),

                                                   -- Persons
                                                   ('persons_ssn:view'),
                                                   ('persons_ssn:edit'),
                                                   ('persons:view'),
                                                   ('persons:edit'),
                                                   ('persons:view_own'),
                                                   ('persons:edit_own'), -- aspirational
                                                   ('persons:create'),
                                                   ('persons:delete'),

                                                   -- Audit
                                                   ('audit:view'),
                                                   ('audit:view_own'), -- aspirational

                                                   -- Tenancy
                                                   ('tenancy:view'),
                                                   ('tenancy:edit'),
                                                   ('tenancy:create'),
                                                   ('tenancy:delete'),

                                                   -- Tenants
                                                   ('tenants:create'),
                                                   ('tenants:view'),
                                                   ('tenants:edit'),
                                                   ('tenants:delete'),

                                                   -- Notices
                                                   ('notices:create'),
                                                   ('notices:view'),
                                                   ('notices:edit'),
                                                   ('notices:delete'),

                                                   -- Lots
                                                   ('lots:view'),
                                                   ('lots:edit'),
                                                   ('lots:set_rates'),
                                                   ('lots:create'),
                                                   ('lots:delete'),

                                                   -- Accounts
                                                   ('accounts:view'),
                                                   ('accounts:edit'),

                                                   -- Transactions
                                                   ('transactions:view'),
                                                   ('transactions:edit'),

                                                   -- Vehicles
                                                   ('vehicles:view'),
                                                   ('vehicles:edit'),
                                                   ('vehicles:create'),
                                                   ('vehicles:delete'),

                                                   -- Meters
                                                   ('meters:view'),
                                                   ('meters:edit'),
                                                   ('meters:create'),
                                                   ('meters:delete'),

                                                   -- Meter Billing
                                                   ('meterbills:view'),
                                                   ('meterbills:edit'),
                                                   ('meterbills:create'),
                                                   ('meterbills:delete'),

                                                   -- standard term
                                                   ('terms_template:manage_global'),
                                                   ('terms_template:view'),
                                                   ('terms_template:edit'),
                                                   ('terms_template:create'),
                                                   ('terms_template:delete'),

                                                   -- charge term
                                                   ('tenancy_term:create_migrations'),
                                                   ('tenancy_term:view'),
                                                   ('tenancy_term:edit'),
                                                   ('tenancy_term:create'),
                                                   ('tenancy_term:delete'),
                                                   ('tenancy_term:activate'),
                                                   ('tenancy_term:cancel'),

                                                   -- security deposits
                                                   ('security_deposit:create_migrations'),
                                                   ('security_deposit:view'),
                                                   ('security_deposit:create'),
                                                   ('security_deposit:edit'),
                                                   ('security_deposit:delete'),

                                                   -- property document
                                                   ('property_document:view'),
                                                   ('property_document:assign'),
                                                   ('property_document:unassign'),

                                                   -- documents
                                                   ('document_template:view'),
                                                   ('document_template:create'),
                                                   ('document_template:edit'),
                                                   ('document_template:delete'),

                                                   -- global settings
                                                   ('global_settings:view'),
                                                   ('global_settings:edit'),

                                                   -- instruments
                                                   ('instrument:view'),
                                                   ('instrument:create'),
                                                   ('instrument:edit'),

                                                   -- homes
                                                   ('homes:view'),
                                                   ('homes:create'),
                                                   ('homes:edit'),
                                                   ('homes:delete')
;

-- ── Permission groups ─────────────────────────────────────────────────────────

-- The sections of the role editor, in screen order.
INSERT INTO permission_group (group_key, sort_order) VALUES
    ('staff_access', 1),
    ('people', 2),
    ('tenancies', 3),
    ('properties', 4),
    ('money', 5),
    ('utilities', 6),
    ('documents', 7),
    ('notices', 8),
    ('system', 9);

-- Which group each area belongs to. To move an area, change its row here.
CREATE TEMP TABLE permission_area (
    area       VARCHAR(40) NOT NULL,
    group_key  VARCHAR(40) NOT NULL,
    area_order INT NOT NULL
) ON COMMIT DROP;

INSERT INTO permission_area (area, group_key, area_order) VALUES
    ('agents', 'staff_access', 1),
    ('agent_roles', 'staff_access', 2),
    ('role_permissions', 'staff_access', 3),
    ('permissions', 'staff_access', 4),
    ('persons', 'people', 5),
    ('persons_ssn', 'people', 6),
    ('tenancy', 'tenancies', 7),
    ('tenants', 'tenancies', 8),
    ('tenancy_term', 'tenancies', 9),
    ('security_deposit', 'tenancies', 10),
    ('pets', 'tenancies', 11),
    ('vehicles', 'tenancies', 12),
    ('properties', 'properties', 13),
    ('lots', 'properties', 14),
    ('homes', 'properties', 15),
    ('sitemaps', 'properties', 16),
    ('assignments', 'properties', 17),
    ('accounts', 'money', 18),
    ('transactions', 'money', 19),
    ('meters', 'utilities', 20),
    ('meterbills', 'utilities', 21),
    ('terms_template', 'documents', 22),
    ('document_template', 'documents', 23),
    ('property_document', 'documents', 24),
    ('instrument', 'documents', 25),
    ('notices', 'notices', 26),
    ('audit', 'system', 27),
    ('global_settings', 'system', 28);

-- Inside a group: by area, then view, create, edit, delete, then the rest in seed order.
INSERT INTO permission (permission_name, group_id, sort_order)
SELECT s.permission_name,
       g.uuid,
       ROW_NUMBER() OVER (
           PARTITION BY g.uuid
           ORDER BY a.area_order,
                    CASE split_part(s.permission_name, ':', 2)
                        WHEN 'view'   THEN 1
                        WHEN 'create' THEN 2
                        WHEN 'edit'   THEN 3
                        WHEN 'delete' THEN 4
                        ELSE 5
                    END,
                    s.seed_order)
FROM permission_seed s
JOIN permission_area a ON a.area = split_part(s.permission_name, ':', 1)
JOIN permission_group g ON g.group_key = a.group_key;

-- Stop the migration if any permission was left without a group.
DO $$
DECLARE
    missing TEXT;
BEGIN
    SELECT string_agg(s.permission_name, ', ') INTO missing
    FROM permission_seed s
    WHERE NOT EXISTS (SELECT 1 FROM permission p WHERE p.permission_name = s.permission_name);

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'These permissions have no group. Add their area to permission_area: %', missing;
    END IF;
END $$;

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
                            'agents:edit',
                            'agents:view_own',
                            'agents:edit_own',
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
                            'sitemaps:edit',
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
                            'notices:create',
                            'notices:edit',
                            'notices:view',
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
                            'global_settings:view',
                            'instrument:view',
                            'instrument:create',
                            'instrument:edit',
                            'homes:view',
                            'homes:create',
                            'homes:edit',
                            'security_deposit:edit',
                            'security_deposit:view',
                            'security_deposit:create'
    );

-- Property Manager
INSERT INTO role_permission (uuid, role_id, permission_id)
SELECT uuidv7(), r.uuid, p.uuid
FROM agent_role r, permission p
WHERE r.role_name = 'Property Manager'
  AND p.permission_name IN (
                            'agents:view',
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
                            'notices:view',
                            'tenancy_term:view',
                            'vehicles:view',
                            'meters:view',
                            'meterbills:view',
                            'meterbills:edit',
                            'instrument:view',
                            'homes:view',
                            'security_deposit:view'
    );

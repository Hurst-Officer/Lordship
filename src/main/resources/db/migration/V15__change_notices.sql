-- ============================================================
-- V15: Change notices
--
-- Every write to a table Glade shows tells Lordship, whoever made it: Glade, psql, an import, a billing job.
-- Postgres sends the notice (pg_notify on channel lordship_change) only when the transaction commits, drops it
-- when the transaction rolls back, and sends one notice where a transaction repeats the same one.
-- The notice is { "t": table, "r": { the row's uuid and the ids of its parents } }, never the row's values.
-- Lordship (presence ChangeNotices) turns it into the Glade places that show the row and tells every open
-- Glade window, which loads those screens again.
--
-- A table added later that Glade shows watches itself in its own migration:  SELECT lordship_watch('new_table');
-- (presence ChangeNoticesIT fails for a table with a property_id or lot_id column that does not.)
--
-- A NEW GLADE CONCERN touches three things. Each one fails without a word when missed: the concern's screens
-- still load again after a save made in the same window, and never after a change made anywhere else.
--   1. The concern's tables are watched. A table with a property_id or lot_id column is watched by the SELECT at
--      the end of this file. A table keyed another way (meter_read by its meter, utility_bill_line by its bill)
--      is NOT: add the table to the list in that SELECT.
--   2. Every id column a place is built from is in BOTH arrays of lordship_notify_change below, and in
--      ChangePlaces.CARRIED_IDS. A column missing from the arrays is dropped from the notice.
--   3. ChangePlaces.placesOf has a case for the table that names the concern's own places. A table without a
--      case tells the Properties places only.
-- ChangePlacesTest and ChangeNoticesIT fail for a table in AuditService.SCREEN_TABLES that misses one of the three.
-- ============================================================

CREATE FUNCTION lordship_notify_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    r   jsonb;
    ids jsonb;
    was jsonb;
BEGIN
    IF TG_OP = 'DELETE' THEN
        r := to_jsonb(OLD);
    ELSE
        r := to_jsonb(NEW);
    END IF;
    -- The row's id and every parent id a place is built from. A column the table does not have is left out.
    SELECT jsonb_object_agg(k, r -> k) INTO ids
    FROM unnest(ARRAY['uuid', 'property_id', 'lot_id', 'agent_id', 'role_id', 'person_id', 'mobile_home_id',
                      'template', 'section', 'property', 'document_template', 'assignment']) AS k
    WHERE jsonb_typeof(r -> k) = 'string';
    PERFORM pg_notify('lordship_change', jsonb_build_object('t', TG_TABLE_NAME, 'r', COALESCE(ids, '{}'::jsonb))::text);
    -- An UPDATE that moves the row to another parent (a home to another lot, or off its lot) sends a second
    -- notice with the ids the row had before, so the place the row left loads again too.
    IF TG_OP = 'UPDATE' THEN
        r := to_jsonb(OLD);
        SELECT jsonb_object_agg(k, r -> k) INTO was
        FROM unnest(ARRAY['uuid', 'property_id', 'lot_id', 'agent_id', 'role_id', 'person_id', 'mobile_home_id',
                          'template', 'section', 'property', 'document_template', 'assignment']) AS k
        WHERE jsonb_typeof(r -> k) = 'string';
        IF was IS DISTINCT FROM ids THEN
            PERFORM pg_notify('lordship_change', jsonb_build_object('t', TG_TABLE_NAME, 'r', COALESCE(was, '{}'::jsonb))::text);
        END IF;
    END IF;
    RETURN NULL;
END
$$;

-- Puts the notice on a table (again, if it was there)
CREATE FUNCTION lordship_watch(table_name text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('DROP TRIGGER IF EXISTS lordship_change ON %I', table_name);
    EXECUTE format('CREATE TRIGGER lordship_change AFTER INSERT OR UPDATE OR DELETE ON %I '
                   'FOR EACH ROW EXECUTE FUNCTION lordship_notify_change()', table_name);
END
$$;

-- What Glade shows: every table that belongs to a property or a lot, the people and their access, and the documents.
-- Left out: caches the server fills in bulk and screens never read row by row (elevation, footprint queue),
-- the map editor's own undo log, and spent refresh tokens.
SELECT lordship_watch(watched.table_name)
FROM (
    SELECT c.table_name
    FROM information_schema.columns c
    JOIN information_schema.tables t
      ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'
    WHERE c.table_schema = current_schema()
      AND c.column_name IN ('property_id', 'lot_id')
    UNION
    SELECT unnest(ARRAY[
        'property',
        'person', 'agent', 'agent_session', 'agent_role', 'role_permission', 'granted_role', 'denied_permission',
        'mobile_home_secured_party',
        'document_template', 'document_style', 'document_section', 'template_clause',
        'property_document_assignment', 'property_document_customization',
        -- a property's deals (Glade's Rates and deals). Keyed by "property", so not found by the columns above.
        'terms_template'])
) AS watched
WHERE watched.table_name NOT IN ('site_elevation', 'property_footprint_stale', 'map_feature_edit',
                                 'agent_session_spent_token');

-- A session's token is swapped every 15 minutes while Glade is open. Only a sign-in and a sign-out are news,
-- so agent_session tells on insert, delete and ended_at alone.
DROP TRIGGER lordship_change ON agent_session;
CREATE TRIGGER lordship_change AFTER INSERT OR DELETE OR UPDATE OF ended_at ON agent_session
    FOR EACH ROW EXECUTE FUNCTION lordship_notify_change();

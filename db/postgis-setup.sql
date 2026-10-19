-- Run ONCE per database, as a superuser (postgres). Not a migration: creating
-- an extension needs superuser, and the application's role is not one.
--
--   psql -U postgres -f postgis-setup.sql
--
-- The search_path settings below cover ordinary application connections.
-- Migrations do not rely on them: Flyway sets its own search_path, so V6 writes
-- postgis.geometry and postgis.ST_IsValid in full.
--
-- PostGIS goes in its own schema rather than in public, so that
--   DROP SCHEMA public CASCADE
-- leaves it standing. Otherwise every flyway reset needs a superuser again.

-- Set this to the role the application connects as (DB_USERNAME).
-- \set app_user 'eman2022'
\set app_user 'postgres'

\c lordship
CREATE SCHEMA IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis SCHEMA postgis;
ALTER DATABASE lordship SET search_path = public, postgis;
ALTER ROLE :app_user IN DATABASE lordship SET search_path = public, postgis;
GRANT USAGE ON SCHEMA postgis TO :app_user;

GRANT USAGE ON SCHEMA postgis TO PUBLIC;

\c lordship_test
CREATE SCHEMA IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS postgis SCHEMA postgis;
ALTER DATABASE lordship_test SET search_path = public, postgis;
ALTER ROLE :app_user IN DATABASE lordship_test SET search_path = public, postgis;
GRANT USAGE ON SCHEMA postgis TO :app_user;

GRANT USAGE ON SCHEMA postgis TO PUBLIC;
-- If postgis was already installed into public, move it first:
--   ALTER EXTENSION postgis SET SCHEMA postgis;
-- and if that complains, on a dev database it is simpler to drop and recreate:
--   DROP EXTENSION postgis CASCADE;   -- drops every geometry column with it
--   CREATE EXTENSION postgis SCHEMA postgis;

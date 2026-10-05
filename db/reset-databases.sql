-- The flyway reset. Safe to run as the ordinary user now: postgis lives in the
-- postgis schema, so dropping public no longer takes it with it.
-- The search_path set by postgis-setup.sql is on the DATABASE, so it survives too.

\c lordship_test
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO PUBLIC;

\c lordship
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
GRANT ALL ON SCHEMA public TO PUBLIC;

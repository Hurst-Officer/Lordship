-- ============================================================
-- V16: When an agent can be reached
-- ============================================================

-- In the agent's own words ("Mon-Fri 8-5, Sat on call"). Null until they set it.
-- The agent edits it on their own page in Glade (agents:edit_own), along with work_phone.
ALTER TABLE agent ADD COLUMN hours_available VARCHAR(200);

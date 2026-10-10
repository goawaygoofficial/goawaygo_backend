-- Baseline migration: establishes Flyway's history table on a fresh database.
-- Schema changes are added as new files (V2__..., V3__...); never edit a migration that has been merged.
COMMENT ON SCHEMA public IS 'Goaway application schema, managed by Flyway (src/main/resources/db/migration)';

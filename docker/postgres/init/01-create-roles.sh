#!/bin/bash
# Creates the GoAwayGo database and its least-privilege roles.
# Runs once, when the Postgres data volume is first initialised (docker-entrypoint-initdb.d).
# Used by compose.yaml (local dev) and by the Testcontainers tests, so both get the same setup, and by
# scripts/setup-hosted-db.sh for hosted databases (Neon, later Cloud SQL), where it runs as the provider's admin
# role instead of a superuser.
#
#   goaway_owner  owns the database and schema; used only by Flyway to run migrations (DDL)
#   goaway_app    used by the running backend; can read/write table data, cannot create/alter/drop
set -euo pipefail

: "${GOAWAY_DB_NAME:?}" "${GOAWAY_OWNER_USER:?}" "${GOAWAY_OWNER_PASSWORD:?}" "${GOAWAY_APP_USER:?}" "${GOAWAY_APP_PASSWORD:?}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "${POSTGRES_DB:-postgres}" \
  -v db="$GOAWAY_DB_NAME" \
  -v owner="$GOAWAY_OWNER_USER" -v owner_pw="$GOAWAY_OWNER_PASSWORD" \
  -v app="$GOAWAY_APP_USER" -v app_pw="$GOAWAY_APP_PASSWORD" <<'SQL'
CREATE ROLE :"owner" LOGIN PASSWORD :'owner_pw';
CREATE ROLE :"app" LOGIN PASSWORD :'app_pw';
-- Hosted Postgres has no superuser: the admin role must be a member of the owner role to create a database owned
-- by it and to set its default privileges. Harmless for a superuser.
GRANT :"owner" TO CURRENT_USER;

CREATE DATABASE :"db" OWNER :"owner";
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO :"app";

\connect :"db"

-- The public schema belongs to the database owner (PostgreSQL 15+). Nobody else may create objects in it.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO :"app";

-- Every table/sequence the owner creates (i.e. every Flyway migration) is automatically usable by the app role.
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO :"app";
SQL

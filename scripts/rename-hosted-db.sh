#!/usr/bin/env bash
# Renames a hosted environment's database and its two roles (rename phase 3): goaway -> goawaygo,
# goaway_owner -> goawaygo_owner, goaway_app -> goawaygo_app. Data, permissions and passwords stay as they are
# (SCRAM passwords survive a role rename). Nothing is printed except progress.
#
#   Usage:  scripts/rename-hosted-db.sh
#   Needs:  Docker (runs psql from the postgres image).
#   Asks:   the database host and the provider's admin user + password (Neon: Dashboard -> Connect).
#
# The backend can't reach the database between this script and the next deploy (a few minutes): it still uses
# the old names until the DB_NAME / DB_OWNER_USER / DB_APP_USER repo variables are removed and it is redeployed.
set -euo pipefail

OLD_DB="${OLD_DB:-goaway}" NEW_DB="${NEW_DB:-goawaygo}"
OLD_OWNER="${OLD_OWNER:-goaway_owner}" NEW_OWNER="${NEW_OWNER:-goawaygo_owner}"
OLD_APP="${OLD_APP:-goaway_app}" NEW_APP="${NEW_APP:-goawaygo_app}"

read -r -p "Database host (e.g. ep-xxx.ap-southeast-1.aws.neon.tech, without -pooler): " DB_HOST
read -r -p "Admin user [neondb_owner]: " ADMIN_USER
ADMIN_USER="${ADMIN_USER:-neondb_owner}"
read -r -p "Admin database (not the one being renamed) [neondb]: " ADMIN_DB
ADMIN_DB="${ADMIN_DB:-neondb}"
read -r -s -p "Admin password (not shown): " ADMIN_PASSWORD
echo
[[ "$ADMIN_DB" != "$OLD_DB" ]] || { echo "Connect to a different database than $OLD_DB." >&2; exit 1; }

echo "==> Renaming database $OLD_DB -> $NEW_DB and roles $OLD_OWNER -> $NEW_OWNER, $OLD_APP -> $NEW_APP on $DB_HOST"
# The password goes in as an environment variable (not an argument), so it doesn't show up in process lists.
MSYS_NO_PATHCONV=1 PGPASSWORD="$ADMIN_PASSWORD" \
docker run --rm -i \
  -e PGHOST="$DB_HOST" -e PGSSLMODE=require -e PGPASSWORD -e PGUSER="$ADMIN_USER" -e PGDATABASE="$ADMIN_DB" \
  postgres:17.11 psql -v ON_ERROR_STOP=1 -q \
    -v old_db="$OLD_DB" -v new_db="$NEW_DB" \
    -v old_owner="$OLD_OWNER" -v new_owner="$NEW_OWNER" -v old_app="$OLD_APP" -v new_app="$NEW_APP" <<'SQL'
\set QUIET on
-- Stop new connections, then close the backend's open ones (it reconnects after the deploy).
ALTER DATABASE :"old_db" ALLOW_CONNECTIONS false;
SELECT count(pg_terminate_backend(pid)) AS closed_connections
  FROM pg_stat_activity WHERE datname = :'old_db' AND pid <> pg_backend_pid();
SELECT pg_sleep(2);
ALTER DATABASE :"old_db" RENAME TO :"new_db";
ALTER DATABASE :"new_db" ALLOW_CONNECTIONS true;
ALTER ROLE :"old_owner" RENAME TO :"new_owner";
ALTER ROLE :"old_app" RENAME TO :"new_app";
\set QUIET off
\echo Done. Database and roles now:
SELECT datname AS database, pg_get_userbyid(datdba) AS owner FROM pg_database WHERE datname = :'new_db';
SELECT rolname AS role FROM pg_roles WHERE rolname IN (:'new_owner', :'new_app') ORDER BY 1;
SQL

cat <<EOF

Next: tell Claude "database renamed". The repo variables DB_NAME / DB_OWNER_USER / DB_APP_USER are then removed
and the backend is redeployed with the new names. The admin password you typed was used once and not stored.
EOF

#!/usr/bin/env bash
# One-time setup of a hosted PostgreSQL database (Neon now, Cloud SQL at launch) for a GoAwayGo environment:
# creates the goawaygo database and its two least-privilege roles with fresh random passwords, and stores the
# passwords straight into Google Secret Manager. No password is printed or written to disk.
#
#   Usage:  scripts/setup-hosted-db.sh <project-id>
#           GOAWAYGO_ENV=dev scripts/setup-hosted-db.sh <project-id>     (or test; default prod)
#   Environments (MVP 2, 5.3): prod uses the database goawaygo, roles goawaygo_owner / goawaygo_app and secrets
#   goawaygo-db-*-password; dev and test use goawaygo_<env>, goawaygo_<env>_owner / goawaygo_<env>_app and
#   goawaygo-<env>-db-*-password.
#   Needs:  Docker (runs psql from the postgres image, so nothing to install), gcloud signed in.
#   Asks:   the database host and the provider's admin user + password (Neon: Dashboard -> Connect).
#
# Uses the same role script as local development (docker/postgres/init/01-create-roles.sh):
#   goawaygo_owner  owns the schema, used only by Flyway migrations
#   goawaygo_app    used by the running backend: reads/writes data, cannot change the schema
set -euo pipefail

PROJECT_ID="${1:?Usage: scripts/setup-hosted-db.sh <project-id>}"
cd "$(dirname "$0")/.."

ENVIRONMENT="${GOAWAYGO_ENV:-prod}"
case "$ENVIRONMENT" in
  prod) DB_NAME=goawaygo; OWNER_USER=goawaygo_owner; APP_USER=goawaygo_app; SECRET_PREFIX=goawaygo ;;
  dev | test)
    DB_NAME="goawaygo_$ENVIRONMENT"; OWNER_USER="goawaygo_${ENVIRONMENT}_owner"; APP_USER="goawaygo_${ENVIRONMENT}_app"
    SECRET_PREFIX="goawaygo-$ENVIRONMENT" ;;
  *) echo "GOAWAYGO_ENV must be dev, test or prod." >&2; exit 1 ;;
esac
echo "Environment: $ENVIRONMENT (database $DB_NAME)"

read -r -p "Database host (e.g. ep-xxx.ap-southeast-1.aws.neon.tech, without -pooler): " DB_HOST
read -r -p "Admin user [neondb_owner]: " ADMIN_USER
ADMIN_USER="${ADMIN_USER:-neondb_owner}"
read -r -p "Admin database [neondb]: " ADMIN_DB
ADMIN_DB="${ADMIN_DB:-neondb}"
read -r -s -p "Admin password (not shown): " ADMIN_PASSWORD
echo

random_password() { openssl rand -base64 33 | tr '+/' '-_' | tr -d '='; }
OWNER_PASSWORD="$(random_password)"
APP_PASSWORD="$(random_password)"

echo "==> Creating database '$DB_NAME' and roles $OWNER_USER / $APP_USER on $DB_HOST"
# Values go in as environment variables (not command-line arguments), so they don't show up in process lists.
MSYS_NO_PATHCONV=1 \
PGPASSWORD="$ADMIN_PASSWORD" GOAWAYGO_OWNER_PASSWORD="$OWNER_PASSWORD" GOAWAYGO_APP_PASSWORD="$APP_PASSWORD" \
docker run --rm \
  -e PGHOST="$DB_HOST" -e PGSSLMODE=require -e PGPASSWORD \
  -e POSTGRES_USER="$ADMIN_USER" -e POSTGRES_DB="$ADMIN_DB" \
  -e GOAWAYGO_DB_NAME="$DB_NAME" -e GOAWAYGO_OWNER_USER="$OWNER_USER" -e GOAWAYGO_OWNER_PASSWORD \
  -e GOAWAYGO_APP_USER="$APP_USER" -e GOAWAYGO_APP_PASSWORD \
  -v "$PWD/docker/postgres/init:/init:ro" \
  postgres:17.11 bash /init/01-create-roles.sh

store_secret() { # name, value (from stdin, never echoed)
  local name="$1"
  if ! gcloud secrets describe "$name" --project "$PROJECT_ID" >/dev/null 2>&1; then
    gcloud secrets create "$name" --project "$PROJECT_ID" --replication-policy automatic >/dev/null
  fi
  gcloud secrets versions add "$name" --project "$PROJECT_ID" --data-file=- >/dev/null
  echo "    stored secret $name"
}

echo "==> Storing the role passwords in Secret Manager ($PROJECT_ID)"
printf '%s' "$OWNER_PASSWORD" | store_secret "$SECRET_PREFIX-db-owner-password"
printf '%s' "$APP_PASSWORD" | store_secret "$SECRET_PREFIX-db-app-password"

cat <<EOF

Done. The GitHub variable for this environment (not a secret):
  DB_HOST = ${DB_HOST}
The admin password you typed was used once and not stored; keep it in your password manager.
EOF

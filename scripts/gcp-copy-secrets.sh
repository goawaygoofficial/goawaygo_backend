#!/usr/bin/env bash
# Copies production's secrets from one Google Cloud project to another (MVP 2, 5.3 part C: prod moves from
# goawaygo-staging to goawaygo-prod), then lets the backend's service account in the new project read them.
# Values go straight from one project to the other in memory: never printed, written to disk or passed as arguments.
# (Replaces the one-time rename copy of 2026-10, goaway-* -> goawaygo-*, which is done.)
#
#   Usage:  scripts/gcp-copy-secrets.sh <from-project> <to-project>
#           e.g. scripts/gcp-copy-secrets.sh goawaygo-staging goawaygo-prod
#   Needs:  gcloud CLI, signed in as an owner of both projects; scripts/gcp-bootstrap.sh run for <to-project> first.
#
# Copies every secret named goawaygo-* except dev's and test's own (goawaygo-dev-*, goawaygo-test-*): prod's database
# passwords and login-token keys (the same keys, so nobody is signed out), and the shared Google, Resend, R2 and
# outside API keys. Only the latest version is copied. A secret that already exists in <to-project> is left alone,
# so it is safe to re-run. After the move, change a shared key in both projects (scripts/gcp-secrets.sh).
set -euo pipefail

FROM="${1:?Usage: scripts/gcp-copy-secrets.sh <from-project> <to-project>}"
TO="${2:?Usage: scripts/gcp-copy-secrets.sh <from-project> <to-project>}"
[ "$FROM" != "$TO" ] || { echo "The two projects must differ." >&2; exit 1; }
RUNTIME_SA="goawaygo-backend@${TO}.iam.gserviceaccount.com"
gcloud iam service-accounts describe "$RUNTIME_SA" --project "$TO" >/dev/null 2>&1 \
  || { echo "$RUNTIME_SA doesn't exist: run scripts/gcp-bootstrap.sh $TO first." >&2; exit 1; }

names="$(gcloud secrets list --project "$FROM" --format 'value(name)' \
  | sed 's#.*/##' | grep '^goawaygo-' | grep -Ev '^goawaygo-(dev|test)-' || true)"
[ -n "$names" ] || { echo "No goawaygo-* secrets in $FROM." >&2; exit 1; }

copied=0 kept=0
for name in $names; do
  if gcloud secrets describe "$name" --project "$TO" >/dev/null 2>&1; then
    echo "kept   $name (already in $TO)"
    kept=$((kept + 1))
  else
    gcloud secrets create "$name" --project "$TO" --replication-policy automatic >/dev/null
    # Byte-exact copy (Google's documented way): the raw payload, base64url-decoded, no newline added. The payload
    # comes without "=" padding, which base64 -d needs. It stays encoded in memory and is never printed. On Windows
    # gcloud ends its output with "\r", which base64 rejects: removed first.
    data="$(gcloud secrets versions access latest --secret "$name" --project "$FROM" --format 'get(payload.data)' \
      | tr -d '\r\n' | tr '_-' '/+')"
    while ((${#data} % 4)); do data+="="; done
    printf '%s' "$data" | base64 -d | gcloud secrets versions add "$name" --project "$TO" --data-file=- >/dev/null
    unset data
    echo "copied $name"
    copied=$((copied + 1))
  fi
  gcloud secrets add-iam-policy-binding "$name" --project "$TO" \
    --member "serviceAccount:$RUNTIME_SA" --role roles/secretmanager.secretAccessor >/dev/null
done
echo "Done: $copied copied, $kept already there; goawaygo-backend in $TO can read all of them."

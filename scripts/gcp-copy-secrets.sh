#!/usr/bin/env bash
# Copies the backend's secrets to their new names in Google Secret Manager (rename phase 4):
# goaway-<x> -> goawaygo-<x>, latest version only. Each value goes straight from one secret to the other inside
# this pipe: it is never printed, written to disk or passed as an argument. The old secrets are left in place
# (removed later, after the new setup is confirmed working).
#
#   Usage:  scripts/gcp-copy-secrets.sh <project-id>
set -euo pipefail
PROJECT_ID="${1:?Usage: scripts/gcp-copy-secrets.sh <project-id>}"

for suffix in db-owner-password db-app-password jwt-private-key jwt-public-key google-client-secret resend-api-key; do
  old="goaway-$suffix" new="goawaygo-$suffix"
  if ! gcloud secrets describe "$old" --project "$PROJECT_ID" >/dev/null 2>&1; then
    echo "skipped $old (doesn't exist)"
    continue
  fi
  if ! gcloud secrets describe "$new" --project "$PROJECT_ID" >/dev/null 2>&1; then
    gcloud secrets create "$new" --project "$PROJECT_ID" --replication-policy automatic >/dev/null
  fi
  # Byte-exact copy (Google's documented way): the raw payload, base64url-decoded; no newline is added.
  gcloud secrets versions access latest --secret "$old" --project "$PROJECT_ID" --format 'get(payload.data)' \
    | tr '_-' '/+' | base64 -d \
    | gcloud secrets versions add "$new" --project "$PROJECT_ID" --data-file=- >/dev/null
  echo "copied $old -> $new"
done
echo "Done. Tell Claude \"secrets copied\"."

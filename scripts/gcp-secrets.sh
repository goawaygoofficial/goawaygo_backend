#!/usr/bin/env bash
# Creates the backend's other secrets in Google Secret Manager for one environment (see docs/deployment.md).
# Nothing is printed or written to disk; typed values are hidden.
#
#   Usage:  scripts/gcp-secrets.sh <project-id> jwt              new ES256 key pair for login tokens
#           scripts/gcp-secrets.sh <project-id> google           Google Web client secret (website Google button)
#           scripts/gcp-secrets.sh <project-id> resend           Resend API key (email)
#           scripts/gcp-secrets.sh <project-id> provider <name>  an outside API's key (goaway-api-<name>), readable by
#                                                                the backend right away; then add <name> to the repo
#                                                                variable EXTERNAL_API_PROVIDERS (docs/deployment.md)
#           scripts/gcp-secrets.sh <project-id> access           let the backend's service account read them all
#
# Running "jwt" again rotates the key pair: everyone has to sign in again (access tokens last 20 minutes and refresh
# tokens are checked against the database, so the effect is limited to that).
set -euo pipefail

PROJECT_ID="${1:?Usage: scripts/gcp-secrets.sh <project-id> jwt|google|resend|provider <name>|access}"
WHAT="${2:?Usage: scripts/gcp-secrets.sh <project-id> jwt|google|resend|provider <name>|access}"
RUNTIME_SA="goaway-backend@${PROJECT_ID}.iam.gserviceaccount.com"
ALL_SECRETS=(goaway-db-owner-password goaway-db-app-password goaway-jwt-private-key goaway-jwt-public-key
  goaway-google-client-secret goaway-resend-api-key)

store_secret() { # name; value on stdin
  local name="$1"
  if ! gcloud secrets describe "$name" --project "$PROJECT_ID" >/dev/null 2>&1; then
    gcloud secrets create "$name" --project "$PROJECT_ID" --replication-policy automatic >/dev/null
  fi
  gcloud secrets versions add "$name" --project "$PROJECT_ID" --data-file=- >/dev/null
  echo "stored secret $name"
}

read_hidden() { # prompt -> value
  local value
  read -r -s -p "$1 (not shown): " value
  echo >&2
  [[ -n "$value" ]] || { echo "Nothing entered." >&2; exit 1; }
  printf '%s' "$value"
}

case "$WHAT" in
  jwt)
    # ES256 (P-256) pair, base64 DER: PKCS#8 private, X.509 public, as goaway.auth.jwt.* expects.
    key="$(openssl ecparam -name prime256v1 -genkey -noout)"
    printf '%s\n' "$key" | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\n' \
      | store_secret goaway-jwt-private-key
    printf '%s\n' "$key" | openssl ec -pubout -outform DER 2>/dev/null | base64 | tr -d '\n' \
      | store_secret goaway-jwt-public-key
    ;;
  google)
    read_hidden "Google Web client secret (GOCSPX-...)" | store_secret goaway-google-client-secret
    ;;
  resend)
    read_hidden "Resend API key (re_...)" | store_secret goaway-resend-api-key
    ;;
  provider)
    PROVIDER="${3:?Usage: scripts/gcp-secrets.sh <project-id> provider <name>   (e.g. opentripmap)}"
    [[ "$PROVIDER" =~ ^[a-z0-9]+$ ]] || { echo "Provider names are lowercase letters and digits only." >&2; exit 1; }
    read_hidden "$PROVIDER API key" | store_secret "goaway-api-$PROVIDER"
    gcloud secrets add-iam-policy-binding "goaway-api-$PROVIDER" --project "$PROJECT_ID"       --member "serviceAccount:$RUNTIME_SA" --role roles/secretmanager.secretAccessor >/dev/null
    echo "goaway-backend can read goaway-api-$PROVIDER"
    ;;
  access)
    for name in "${ALL_SECRETS[@]}"; do
      if gcloud secrets describe "$name" --project "$PROJECT_ID" >/dev/null 2>&1; then
        gcloud secrets add-iam-policy-binding "$name" --project "$PROJECT_ID" \
          --member "serviceAccount:$RUNTIME_SA" --role roles/secretmanager.secretAccessor >/dev/null
        echo "goaway-backend can read $name"
      else
        echo "skipped $name (not created yet)"
      fi
    done
    ;;
  *)
    echo "Unknown option: $WHAT (use jwt, google, resend, provider or access)" >&2
    exit 1
    ;;
esac

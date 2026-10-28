#!/usr/bin/env bash
# Creates the backend's other secrets in Google Secret Manager for one environment (see docs/deployment.md).
# Nothing is printed or written to disk; typed values are hidden.
#
#   Usage:  scripts/gcp-secrets.sh <project-id> jwt              new ES256 key pair for login tokens
#           scripts/gcp-secrets.sh <project-id> google           Google Web client secret (website Google button)
#           scripts/gcp-secrets.sh <project-id> resend           Resend API key (email)
#           scripts/gcp-secrets.sh <project-id> provider <name>  an outside API's key (goawaygo-api-<name>), readable by
#                                                                the backend right away; then add <name> to the repo
#                                                                variable EXTERNAL_API_PROVIDERS (docs/deployment.md)
#           scripts/gcp-secrets.sh <project-id> r2               Cloudflare R2 access key for Wanderbook photos
#                                                                (Access Key ID and Secret Access Key)
#           scripts/gcp-secrets.sh <project-id> access           let the backend's service account read them all
#
# Running "jwt" again rotates the key pair: everyone has to sign in again (access tokens last 20 minutes and refresh
# tokens are checked against the database, so the effect is limited to that).
set -euo pipefail

PROJECT_ID="${1:?Usage: scripts/gcp-secrets.sh <project-id> jwt|google|resend|provider <name>|r2|access}"
WHAT="${2:?Usage: scripts/gcp-secrets.sh <project-id> jwt|google|resend|provider <name>|r2|access}"
RUNTIME_SA="goawaygo-backend@${PROJECT_ID}.iam.gserviceaccount.com"
ALL_SECRETS=(goawaygo-db-owner-password goawaygo-db-app-password goawaygo-jwt-private-key goawaygo-jwt-public-key
  goawaygo-google-client-secret goawaygo-resend-api-key goawaygo-r2-access-key-id goawaygo-r2-secret-access-key)

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
    # ES256 (P-256) pair, base64 DER: PKCS#8 private, X.509 public, as goawaygo.auth.jwt.* expects.
    key="$(openssl ecparam -name prime256v1 -genkey -noout)"
    printf '%s\n' "$key" | openssl pkcs8 -topk8 -nocrypt -outform DER | base64 | tr -d '\n' \
      | store_secret goawaygo-jwt-private-key
    printf '%s\n' "$key" | openssl ec -pubout -outform DER 2>/dev/null | base64 | tr -d '\n' \
      | store_secret goawaygo-jwt-public-key
    ;;
  google)
    read_hidden "Google Web client secret (GOCSPX-...)" | store_secret goawaygo-google-client-secret
    ;;
  resend)
    read_hidden "Resend API key (re_...)" | store_secret goawaygo-resend-api-key
    ;;
  provider)
    PROVIDER="${3:?Usage: scripts/gcp-secrets.sh <project-id> provider <name>   (e.g. opentripmap)}"
    [[ "$PROVIDER" =~ ^[a-z0-9]+$ ]] || { echo "Provider names are lowercase letters and digits only." >&2; exit 1; }
    read_hidden "$PROVIDER API key" | store_secret "goawaygo-api-$PROVIDER"
    gcloud secrets add-iam-policy-binding "goawaygo-api-$PROVIDER" --project "$PROJECT_ID"       --member "serviceAccount:$RUNTIME_SA" --role roles/secretmanager.secretAccessor >/dev/null
    echo "goawaygo-backend can read goawaygo-api-$PROVIDER"
    ;;
  r2)
    read_hidden "R2 Access Key ID" | store_secret goawaygo-r2-access-key-id
    read_hidden "R2 Secret Access Key" | store_secret goawaygo-r2-secret-access-key
    for name in goawaygo-r2-access-key-id goawaygo-r2-secret-access-key; do
      gcloud secrets add-iam-policy-binding "$name" --project "$PROJECT_ID"         --member "serviceAccount:$RUNTIME_SA" --role roles/secretmanager.secretAccessor >/dev/null
      echo "goawaygo-backend can read $name"
    done
    ;;
  access)
    for name in "${ALL_SECRETS[@]}"; do
      if gcloud secrets describe "$name" --project "$PROJECT_ID" >/dev/null 2>&1; then
        gcloud secrets add-iam-policy-binding "$name" --project "$PROJECT_ID" \
          --member "serviceAccount:$RUNTIME_SA" --role roles/secretmanager.secretAccessor >/dev/null
        echo "goawaygo-backend can read $name"
      else
        echo "skipped $name (not created yet)"
      fi
    done
    ;;
  *)
    echo "Unknown option: $WHAT (use jwt, google, resend, provider, r2 or access)" >&2
    exit 1
    ;;
esac

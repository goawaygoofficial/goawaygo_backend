#!/usr/bin/env bash
# One-time Google Cloud setup for a GoAwayGo backend environment (see README.md, Deployment).
# Safe to re-run: every step skips what already exists.
#
#   Usage:  scripts/gcp-bootstrap.sh <project-id>        e.g. scripts/gcp-bootstrap.sh goawaygo-staging
#   Needs:  gcloud CLI, signed in with `gcloud auth login` as a project owner.
#
#   Options (environment variables):
#     IMAGES_FROM=<project>  use that project's image registry instead of creating one; images are built once, in
#                            goawaygo-staging (prod: IMAGES_FROM=goawaygo-staging). The deployer and Cloud Run in this
#                            project may read its images. Needs owner rights on that project too.
#     FCM_PROJECT=<project>  let this project's backend send push notifications through that Firebase project
#                            (goaway-483709). Needs owner rights on it too.
#
# Creates:
#   - the APIs the backend uses
#   - Artifact Registry repository "goawaygo" (Docker images), keeping the newest 10 images (not with IMAGES_FROM)
#   - service account goawaygo-backend: the identity the running backend uses (reads its secrets, nothing else)
#   - service account goawaygo-deployer: what GitHub Actions deploys as
#   - Workload Identity Federation: GitHub Actions in goawaygoofficial/goawaygo_backend can act as goawaygo-deployer
#     without any stored key (short-lived tokens only)
set -euo pipefail

PROJECT_ID="${1:?Usage: scripts/gcp-bootstrap.sh <project-id>}"
REGION="${REGION:-asia-southeast1}"
GITHUB_REPO="${GITHUB_REPO:-goawaygoofficial/goawaygo_backend}"
# GitHub's numeric repository ID: unlike the name, it survives renames of the repo or the account.
GITHUB_REPO_ID="${GITHUB_REPO_ID:?Set GITHUB_REPO_ID first: gh api repos/$GITHUB_REPO --jq .id}"
IMAGES_FROM="${IMAGES_FROM:-}"
FCM_PROJECT="${FCM_PROJECT:-}"
REPOSITORY=goawaygo
POOL=github
PROVIDER=goawaygo-backend
RUNTIME_SA="goawaygo-backend@${PROJECT_ID}.iam.gserviceaccount.com"
DEPLOY_SA="goawaygo-deployer@${PROJECT_ID}.iam.gserviceaccount.com"

gcloud config set project "$PROJECT_ID" >/dev/null
PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"

echo "==> Enabling APIs"
gcloud services enable run.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com \
  iam.googleapis.com iamcredentials.googleapis.com sts.googleapis.com cloudresourcemanager.googleapis.com

if [ -z "$IMAGES_FROM" ]; then
  echo "==> Artifact Registry repository '$REPOSITORY' in $REGION"
  if ! gcloud artifacts repositories describe "$REPOSITORY" --location "$REGION" >/dev/null 2>&1; then
    gcloud artifacts repositories create "$REPOSITORY" --repository-format docker --location "$REGION" \
      --description "GoAwayGo container images"
  fi
  # Keep storage near the free 0.5 GB: the 10 newest images, enough for a version to reach test and prod (5.3).
  policy="$(mktemp)"
  cat >"$policy" <<'JSON'
[{"name": "keep-newest-10", "action": {"type": "Keep"}, "mostRecentVersions": {"keepCount": 10}},
 {"name": "delete-older", "action": {"type": "Delete"}, "condition": {"tagState": "ANY"}}]
JSON
  gcloud artifacts repositories set-cleanup-policies "$REPOSITORY" --location "$REGION" --policy "$policy" --no-dry-run
  rm -f "$policy"
fi

echo "==> Service accounts"
gcloud iam service-accounts describe "$RUNTIME_SA" >/dev/null 2>&1 \
  || gcloud iam service-accounts create goawaygo-backend --display-name "GoAwayGo backend (runtime)"
gcloud iam service-accounts describe "$DEPLOY_SA" >/dev/null 2>&1 \
  || gcloud iam service-accounts create goawaygo-deployer --display-name "GoAwayGo deployer (GitHub Actions)"

if [ -z "$IMAGES_FROM" ]; then
  echo "==> Deployer permissions: push images, deploy Cloud Run, run the service as goawaygo-backend"
  gcloud artifacts repositories add-iam-policy-binding "$REPOSITORY" --location "$REGION" \
    --member "serviceAccount:$DEPLOY_SA" --role roles/artifactregistry.writer >/dev/null
else
  echo "==> Images come from $IMAGES_FROM: the deployer and Cloud Run here may read them (not push)"
  # Cloud Run pulls images as this project's Cloud Run service agent; make sure it exists before granting it.
  RUN_AGENT="$(gcloud beta services identity create --service run.googleapis.com --project "$PROJECT_ID" \
    --format 'value(email)' 2>/dev/null || true)"
  RUN_AGENT="${RUN_AGENT:-service-${PROJECT_NUMBER}@serverless-robot-prod.iam.gserviceaccount.com}"
  for member in "serviceAccount:$DEPLOY_SA" "serviceAccount:$RUN_AGENT"; do
    gcloud artifacts repositories add-iam-policy-binding "$REPOSITORY" --project "$IMAGES_FROM" \
      --location "$REGION" --member "$member" --role roles/artifactregistry.reader >/dev/null
  done
  echo "==> Deployer permissions: deploy Cloud Run, run the service as goawaygo-backend"
fi
gcloud projects add-iam-policy-binding "$PROJECT_ID" --member "serviceAccount:$DEPLOY_SA" \
  --role roles/run.developer --condition None >/dev/null
gcloud iam service-accounts add-iam-policy-binding "$RUNTIME_SA" \
  --member "serviceAccount:$DEPLOY_SA" --role roles/iam.serviceAccountUser >/dev/null

if [ -n "$FCM_PROJECT" ]; then
  echo "==> Push notifications: goawaygo-backend may send through the Firebase project $FCM_PROJECT"
  gcloud projects add-iam-policy-binding "$FCM_PROJECT" --member "serviceAccount:$RUNTIME_SA" \
    --role roles/firebasecloudmessaging.admin --condition None >/dev/null
fi

echo "==> Workload Identity Federation for $GITHUB_REPO"
if ! gcloud iam workload-identity-pools describe "$POOL" --location global >/dev/null 2>&1; then
  gcloud iam workload-identity-pools create "$POOL" --location global --display-name "GitHub Actions"
fi
if ! gcloud iam workload-identity-pools providers describe "$PROVIDER" --location global \
  --workload-identity-pool "$POOL" >/dev/null 2>&1; then
  # Only tokens from this exact repository are accepted, matched by ID so renames don't break deploys.
  gcloud iam workload-identity-pools providers create-oidc "$PROVIDER" --location global \
    --workload-identity-pool "$POOL" --display-name "goawaygo_backend" \
    --issuer-uri "https://token.actions.githubusercontent.com" \
    --attribute-mapping "google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_id=assertion.repository_id,attribute.ref=assertion.ref" \
    --attribute-condition "assertion.repository_id == '${GITHUB_REPO_ID}'"
fi
gcloud iam service-accounts add-iam-policy-binding "$DEPLOY_SA" --role roles/iam.workloadIdentityUser \
  --member "principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL}/attribute.repository_id/${GITHUB_REPO_ID}" \
  >/dev/null

cat <<EOF

Done. These GitHub variables are identifiers, not secrets. Set them as repository variables, or for one environment
(prod) in that GitHub environment (Settings -> Environments -> <name> -> Environment variables):
  GCP_PROJECT_ID   = ${PROJECT_ID}
  GCP_REGION       = ${REGION}
  GCP_WIF_PROVIDER = projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL}/providers/${PROVIDER}
  GCP_DEPLOY_SA    = ${DEPLOY_SA}
  GCP_RUNTIME_SA   = ${RUNTIME_SA}
Next: scripts/setup-hosted-db.sh and scripts/gcp-secrets.sh (or scripts/gcp-copy-secrets.sh for a moved environment).
EOF

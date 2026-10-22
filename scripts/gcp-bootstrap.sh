#!/usr/bin/env bash
# One-time Google Cloud setup for a GoAwayGo backend environment (see docs/deployment.md).
# Safe to re-run: every step skips what already exists.
#
#   Usage:  scripts/gcp-bootstrap.sh <project-id>        e.g. scripts/gcp-bootstrap.sh goawaygo-staging
#   Needs:  gcloud CLI, signed in with `gcloud auth login` as a project owner.
#
# Creates:
#   - the APIs the backend uses
#   - Artifact Registry repository "goaway" (Docker images), keeping only the newest images
#   - service account goaway-backend: the identity the running backend uses (reads its secrets, nothing else)
#   - service account goaway-deployer: what GitHub Actions deploys as
#   - Workload Identity Federation: GitHub Actions in goawaygoofficial/goawaygo_backend can act as goaway-deployer
#     without any stored key (short-lived tokens only)
set -euo pipefail

PROJECT_ID="${1:?Usage: scripts/gcp-bootstrap.sh <project-id>}"
REGION="${REGION:-asia-southeast1}"
GITHUB_REPO="${GITHUB_REPO:-goawaygoofficial/goawaygo_backend}"
# GitHub's numeric repository ID: unlike the name, it survives renames of the repo or the account.
GITHUB_REPO_ID="${GITHUB_REPO_ID:?Set GITHUB_REPO_ID first: gh api repos/$GITHUB_REPO --jq .id}"
REPOSITORY=goaway
POOL=github
PROVIDER=goaway-backend
RUNTIME_SA="goaway-backend@${PROJECT_ID}.iam.gserviceaccount.com"
DEPLOY_SA="goaway-deployer@${PROJECT_ID}.iam.gserviceaccount.com"

gcloud config set project "$PROJECT_ID" >/dev/null
PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"

echo "==> Enabling APIs"
gcloud services enable run.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com \
  iam.googleapis.com iamcredentials.googleapis.com sts.googleapis.com cloudresourcemanager.googleapis.com

echo "==> Artifact Registry repository '$REPOSITORY' in $REGION"
if ! gcloud artifacts repositories describe "$REPOSITORY" --location "$REGION" >/dev/null 2>&1; then
  gcloud artifacts repositories create "$REPOSITORY" --repository-format docker --location "$REGION" \
    --description "GoAwayGo container images"
fi
# Keep storage inside the free 0.5 GB: only the 3 newest images are kept.
policy="$(mktemp)"
cat >"$policy" <<'JSON'
[{"name": "keep-newest-3", "action": {"type": "Keep"}, "mostRecentVersions": {"keepCount": 3}},
 {"name": "delete-older", "action": {"type": "Delete"}, "condition": {"tagState": "ANY"}}]
JSON
gcloud artifacts repositories set-cleanup-policies "$REPOSITORY" --location "$REGION" --policy "$policy" --no-dry-run
rm -f "$policy"

echo "==> Service accounts"
gcloud iam service-accounts describe "$RUNTIME_SA" >/dev/null 2>&1 \
  || gcloud iam service-accounts create goaway-backend --display-name "GoAwayGo backend (runtime)"
gcloud iam service-accounts describe "$DEPLOY_SA" >/dev/null 2>&1 \
  || gcloud iam service-accounts create goaway-deployer --display-name "GoAwayGo deployer (GitHub Actions)"

echo "==> Deployer permissions: push images, deploy Cloud Run, run the service as goaway-backend"
gcloud artifacts repositories add-iam-policy-binding "$REPOSITORY" --location "$REGION" \
  --member "serviceAccount:$DEPLOY_SA" --role roles/artifactregistry.writer >/dev/null
gcloud projects add-iam-policy-binding "$PROJECT_ID" --member "serviceAccount:$DEPLOY_SA" \
  --role roles/run.developer --condition None >/dev/null
gcloud iam service-accounts add-iam-policy-binding "$RUNTIME_SA" \
  --member "serviceAccount:$DEPLOY_SA" --role roles/iam.serviceAccountUser >/dev/null

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

Done. Set these GitHub repository variables (Settings -> Secrets and variables -> Actions -> Variables).
They are identifiers, not secrets:
  GCP_PROJECT_ID   = ${PROJECT_ID}
  GCP_REGION       = ${REGION}
  GCP_WIF_PROVIDER = projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL}/providers/${PROVIDER}
  GCP_DEPLOY_SA    = ${DEPLOY_SA}
  GCP_RUNTIME_SA   = ${RUNTIME_SA}
Next: scripts/setup-hosted-db.sh and scripts/gcp-secrets.sh.
EOF

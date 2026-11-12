#!/usr/bin/env bash
# Records a deploy in the version's GitHub Release notes (MVP 2, 5.4): a "Deployments" section with one line per
# environment, replaced on every deploy, so each release shows where that version has run. controller.yaml reads the
# "✅" lines to check that a version passed the environment below before it goes up.
#
#   Usage: record-deployment.sh <version> <environment> <success|failure|cancelled|promoted|passed|failed> <run URL>
#   <environment> is dev, test or prod for a deployment, "AT <env>" or "PT <env>" for test results.
#   Env:   GH_TOKEN with contents: write.
set -euo pipefail

version="${1:?version}" environment="${2:?environment}" result="${3:?result}" run_url="${4:?run URL}"
case "$result" in
  success) status="✅ deployed" ;;
  passed) status="✅ passed" ;;
  promoted) status="✅ promoted (nothing to deploy in this repo)" ;;
  cancelled) status="⏹️ cancelled" ;;
  *) status="❌ failed or not approved" ;;
esac
line="- **$environment**: $status $(date -u '+%Y-%m-%d %H:%M UTC') · [run]($run_url)"
heading="### Deployments"

body="$(gh release view "$version" --json body --jq .body | grep -vF -- "- **$environment**: " || true)"
grep -qxF "$heading" <<<"$body" || body="$(printf '%s\n\n%s' "$body" "$heading")"
notes="$(mktemp)"
printf '%s\n%s\n' "$body" "$line" >"$notes"
gh release edit "$version" --notes-file "$notes" >/dev/null
rm -f "$notes"
echo "$version: $line"

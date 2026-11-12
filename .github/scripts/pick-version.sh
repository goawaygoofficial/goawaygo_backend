#!/usr/bin/env bash
# Which version controller.yaml deploys, and whether it may go up (MVP 2, 5.4).
#
#   test branch → environment test (the environment below is dev)
#   main branch → environment prod (the environment below is test)
#
# Without REQUESTED it picks the version whose files are exactly what the branch holds: develop → test and
# test → main are plain merges, so the branch then holds the develop commit that Release turned into that version.
# With REQUESTED (a redeploy or a rollback) the version must be merged into the branch.
# Either way the version must have passed the environment below: its GitHub Release shows "✅" for the deployment
# there and for the automation (AT) and performance (PT) tests there (record-deployment.sh). A redeploy or rollback to a
# version that already ran in this environment needs nothing new. Repos with nothing to deploy to dev (the app) set
# DEV_DEPLOYS=false.
#
#   Env: BRANCH (test or main), REQUESTED (optional vX.Y.Z), DEV_DEPLOYS (default true), GH_TOKEN, GITHUB_OUTPUT.
set -euo pipefail

: "${BRANCH:?BRANCH is required}"
DEV_DEPLOYS="${DEV_DEPLOYS:-true}"
fail() {
  echo "::error::$1"
  exit 1
}

case "$BRANCH" in
  test) environment=test below=dev ;;
  main) environment=prod below=test ;;
  *) fail "Controller runs on test (deploys to test) or main (deploys to prod), not on $BRANCH. dev gets every version from Release." ;;
esac

if [ -n "${REQUESTED:-}" ]; then
  version="$REQUESTED"
  [[ "$version" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "Not a version: $version (write it like v1.7.0)."
  git rev-parse -q --verify "refs/tags/$version" >/dev/null || fail "$version doesn't exist."
  git merge-base --is-ancestor "$version" HEAD || fail "$version isn't merged into $BRANCH."
else
  tree="$(git rev-parse 'HEAD^{tree}')"
  version=""
  while read -r tag; do
    if [ "$(git rev-parse "$tag^{tree}")" = "$tree" ]; then
      version="$tag"
      break
    fi
  done < <(git tag --merged HEAD --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname | head -n 50)
  [ -n "$version" ] || fail "No version holds exactly what $BRANCH holds. The develop commit merged here gets its \
version from Release on develop: wait for it to finish (or re-run it), then re-run this workflow."
fi

gh release view "$version" >/dev/null 2>&1 || fail "$version has no GitHub Release."
notes="$(gh release view "$version" --json body --jq .body)"
passed() { grep -qF -- "- **$1**: ✅" <<<"$notes"; }
if [ -n "${REQUESTED:-}" ] && passed "$environment"; then
  # Deploying again, or rolling back to a version that already ran here: no new evidence needed.
  echo "$version already ran in $environment."
else
  # The evidence (MVP 2, 5.5/5.6): deployed below, and the automation (AT) and performance (PT) tests passed there.
  missing=()
  if [ "$below" != dev ] || [ "$DEV_DEPLOYS" = true ]; then
    passed "$below" || missing+=("the $below deployment")
  fi
  passed "AT $below" || missing+=("AT in $below")
  passed "PT $below" || missing+=("PT in $below")
  [ "${#missing[@]}" -eq 0 ] || fail "$version can't go to $environment yet: no ✅ in its release for \
$(IFS=,; echo "${missing[*]}" | sed 's/,/, /g'). Run them (Actions → Release or Controller), then re-run this."
fi

echo "environment=$environment" >>"$GITHUB_OUTPUT"
echo "version=$version" >>"$GITHUB_OUTPUT"
{
  echo "### $version → $environment"
  echo "Merged into \`$BRANCH\` and passed $below."
} >>"${GITHUB_STEP_SUMMARY:-/dev/null}"

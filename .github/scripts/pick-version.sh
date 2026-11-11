#!/usr/bin/env bash
# Which version controller.yaml deploys, and whether it may go up (MVP 2, 5.4).
#
#   test branch → environment test (the environment below is dev)
#   main branch → environment prod (the environment below is test)
#
# Without REQUESTED it picks the version whose files are exactly what the branch holds: develop → test and
# test → main are plain merges, so the branch then holds the develop commit that Release turned into that version.
# With REQUESTED (a redeploy or a rollback) the version must be merged into the branch.
# Either way the version must have passed the environment below: its GitHub Release shows "✅" for it
# (record-deployment.sh). Repos with nothing to deploy to dev (the app) set DEV_DEPLOYS=false.
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
if [ "$below" = dev ] && [ "$DEV_DEPLOYS" != true ]; then
  echo "This repo deploys nothing to dev: $version only has to be released."
elif ! gh release view "$version" --json body --jq .body | grep -qF -- "- **$below**: ✅"; then
  fail "$version hasn't passed $below: its release shows no ✅ for $below. It has to be deployed there first."
fi
# 5.5 and 5.6 add the automation (AT) and performance (PT) tests as a further condition here.
echo "::notice::AT and PT in $below: not set up yet (5.5, 5.6), so not checked."

echo "environment=$environment" >>"$GITHUB_OUTPUT"
echo "version=$version" >>"$GITHUB_OUTPUT"
{
  echo "### $version → $environment"
  echo "Merged into \`$BRANCH\` and passed $below."
} >>"${GITHUB_STEP_SUMMARY:-/dev/null}"

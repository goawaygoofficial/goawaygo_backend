#!/usr/bin/env bash
# The version for a commit on develop (MVP 2, 5.2). Prints it as vMAJOR.MINOR.PATCH.
#   A commit that already has a version keeps it (a re-run never makes a second one).
#   Otherwise the latest version goes up by the commit's Conventional Commit title (the squash-merged PR title):
#     feat!: / fix!: ... or "BREAKING CHANGE" in the body  → major      feat: → minor      anything else → patch
# Usage: next-version.sh <commit>          (needs the full history and tags: actions/checkout with fetch-depth: 0)
set -euo pipefail

commit="${1:?usage: next-version.sh <commit>}"
pattern='v[0-9]*.[0-9]*.[0-9]*'

existing="$(git tag --points-at "$commit" --list "$pattern" --sort=-v:refname | head -n 1)"
if [ -n "$existing" ]; then
  echo "$existing"
  exit 0
fi

latest="$(git tag --merged "$commit" --list "$pattern" --sort=-v:refname | head -n 1)"
if [ -z "$latest" ]; then
  echo "No version tag before $commit: tag the starting version first (git tag v1.5.0 <commit>)." >&2
  exit 1
fi

IFS=. read -r major minor patch <<<"${latest#v}"
subject="$(git log -1 --format=%s "$commit")"
body="$(git log -1 --format=%b "$commit")"

if [[ "$subject" =~ ^[a-z]+(\([^\)]*\))?!: ]] || grep -q '^BREAKING CHANGE' <<<"$body"; then
  major=$((major + 1)); minor=0; patch=0
elif [[ "$subject" =~ ^feat(\([^\)]*\))?: ]]; then
  minor=$((minor + 1)); patch=0
else
  patch=$((patch + 1))
fi

echo "v$major.$minor.$patch"

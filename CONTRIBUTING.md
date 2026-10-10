# Contributing to Goaway

These rules apply to all Goaway repositories:
[app](https://github.com/goawayofficial/goaway_app_frontend) ·
[web](https://github.com/goawayofficial/goaway_web_frontend) ·
[backend](https://github.com/goawayofficial/goaway_backend).

## First-time setup

```bash
git clone <repo-url>
cd <repo>
git config core.hooksPath .githooks   # enable the shared git hooks (once per clone)
```

The hooks:

- **pre-commit** scans staged changes for secrets with [Gitleaks](https://github.com/gitleaks/gitleaks).
  Install it once: `winget install Gitleaks.Gitleaks` (Windows) or `brew install gitleaks` (macOS).
  Without it, the hook skips with a warning, and CI still scans.
- **pre-push** blocks pushing directly to `main` or `develop`.

Never bypass hooks with `--no-verify`.

## Branches

| Branch                                                            | Deploys to     | How changes get in                            |
| ----------------------------------------------------------------- | -------------- | --------------------------------------------- |
| `main`                                                            | Production     | Release PR from `develop`, or a `hotfix/*` PR |
| `develop` (default)                                               | Test / staging | PRs from work branches                        |
| `feature/*`, `fix/*`, `chore/*`, `docs/*`, `refactor/*`, `test/*` | Local only     | Your commits                                  |

- Branch from the latest `develop`: `git checkout develop && git pull && git checkout -b feature/short-name`
- Keep branches short-lived (days, not weeks) and focused on one thing.
- Never commit directly to `main` or `develop`.

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add Google sign-in button
fix: stop crash when profile picture is missing
docs: explain how to run the backend locally
chore(deps): bump http to 1.6.0
```

Types: `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `perf`, `ci`, `build`.
Write the subject in the imperative ("add", not "added"), lower case, no trailing period.

## Pull requests

1. Run this repo's checks locally (see **This repository** below). They must pass.
2. Push your branch and open a PR into `develop`. Fill in the template.
3. CI must be green ✅: **CI**, **Secret scan**.
4. Get a review. At least the repo owner approves before merging.
5. **Squash merge** into `develop`. The PR title becomes the commit message, so it must follow Conventional Commits.

Rules:

- **Never merge a PR with a failing (❌) or pending check.**
- Every bug fix or feature includes tests, or the PR explains why not.
- No secrets in code. Config goes in `.env` / environment variables, with placeholders in `.env.example`.
- UI changes include before/after screenshots.

> GitHub Free can't enforce these rules on private repos, so we enforce them ourselves.
> The **Branch guard** workflow fails, and GitHub emails the owner, if a commit lands on `main` or
> `develop` without a merged PR.

## Releases

1. Test `develop` on the test environment and check each feature's acceptance criteria.
2. Open a release PR **`develop` → `main`** titled `release: vX.Y.Z`, and merge it with a **merge commit** (not squash).
3. Tag the release on `main`: `git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z`.
4. Versions follow [Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`.

## Hotfixes

1. Branch `hotfix/short-name` from `main`, fix, and open a PR into `main`.
2. After merging, tag a patch release (`vX.Y.Z+1`).
3. Immediately open a PR **`main` → `develop`** so the fix isn't lost.

## Dependencies

Dependabot opens update PRs into `develop` every Monday. Minor and patch updates are grouped into one PR.
Review them like any other PR: CI must pass, and read the changelog for major updates.

## Security

- Never commit `.env`, keys, keystores, service-account files or passwords. `.gitignore` covers the usual ones.
- If a secret is committed, even on a branch, **rotate it immediately**. Deleting the commit is not enough.
- Report security issues privately to the repo owner, not in a public issue.

## This repository: Backend (Spring Boot)

Requires **JDK 17+** and **Docker Desktop**.

```bash
cp .env.example .env          # then fill in the three passwords (see the file)
docker compose up -d          # local PostgreSQL 17 on 127.0.0.1:5433
./mvnw spring-boot:run        # Windows PowerShell: .mvnw.cmd spring-boot:run
```

Check to run before every PR (the same one CI runs):

```bash
./mvnw verify                 # build + all tests (tests start their own PostgreSQL container)
```

Database rules:

- Schema changes are **Flyway migrations** in `src/main/resources/db/migration` (`V<n>__<description>.sql`).
  Never edit a migration after it has been merged; add a new one.
- The backend connects as `goaway_app` (data only). Only Flyway, as `goaway_owner`, changes the schema.
- Secrets (database passwords) go in `.env` locally and in environment variables when deployed, never in
  `application.properties`.

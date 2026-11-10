# Contributing to GoAwayGo

These rules apply to all GoAwayGo repositories:
[app](https://github.com/goawaygoofficial/goawaygo_app_frontend) ·
[web](https://github.com/goawaygoofficial/goawaygo_web_frontend) ·
[backend](https://github.com/goawaygoofficial/goawaygo_backend).

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
- **pre-push** blocks pushing directly to `develop`, `test` or `main`, and branch names other than `feature/...`
  and `fix/...`.

Never bypass hooks with `--no-verify`.

## Branches

Every change takes the same path: `feature/...` or `fix/...` → `develop` → `test` → `main`.

| Branch              | Is for            | Deploys to        | Merges into                                 |
| ------------------- | ----------------- | ----------------- | ------------------------------------------- |
| `feature/<name>`    | A new feature     | Nowhere (CI only) | `develop`: squash merge, no approval needed |
| `fix/<name>`        | A bug fix         | Nowhere (CI only) | `develop`: squash merge, no approval needed |
| `develop` (default) | Development       | Dev environment   | `test`: merge commit, the owner approves    |
| `test`              | Testing a version | Test environment  | `main`: merge commit, the owner approves    |
| `main`              | Customers         | Production        | —                                           |

- Branch from the latest `develop`: `git switch develop && git pull && git switch -c feature/short-name`.
  Names: lowercase letters, digits, `.`, `_` and `-` after the prefix.
- GitHub rejects new branches with any other name (Dependabot's `dependabot/...` is the exception), direct pushes
  to `develop`, `test` and `main`, force pushes and deleting them.
- Keep branches short-lived (days, not weeks) and focused on one thing.

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
3. The required checks must be green ✅: **CI**, **Secret scan**, **Dependency scan** and **Branch guard**
   (the PR is on an allowed path).
4. **Squash merge** into `develop`. The PR title becomes the commit message, so it must follow Conventional Commits.
5. To move changes up: a PR **`develop` → `test`**, then **`test` → `main`**. Each needs the owner's approval
   (`goawaygoofficial`, the code owner) and is merged with a **merge commit**, never squash, so the branches keep
   the same history.

Rules:

- **Never merge a PR with a failing (❌) or pending check.**
- Every bug fix or feature includes tests, or the PR explains why not.
- No secrets in code. Config goes in `.env` / environment variables, with placeholders in `.env.example`.
- UI changes include before/after screenshots.

These rules are enforced by GitHub repository rulesets (the repos are public, so they are free) and the
**Branch guard** workflow.

## Bug fixes

A bug fix is a `fix/<name>` branch and follows the same path as everything else: `develop` → `test` → `main`.
There are no hotfix branches.

## Dependencies

Dependabot opens update PRs into `develop` every Monday. Minor and patch updates are grouped into one PR.
Review them like any other PR: CI must pass, and read the changelog for major updates.

## Security

- Never commit `.env`, keys, keystores, service-account files or passwords. `.gitignore` covers the usual ones.
- If a secret is committed, even on a branch, **rotate it immediately**. Deleting the commit is not enough.
  The repos are public: anything pushed can be read and copied at once.
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
- The backend connects as `goawaygo_app` (data only). Only Flyway, as `goawaygo_owner`, changes the schema.
- Secrets (database passwords) go in `.env` locally and in environment variables when deployed, never in
  `application.properties`.

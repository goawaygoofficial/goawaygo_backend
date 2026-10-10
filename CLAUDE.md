<!-- Copy of Goaway/CLAUDE.md (workspace root, shared by all repos). Keep the copies in sync. -->

# Goaway

Workspace with three separate git repos. Start Claude sessions in this `Goaway` folder so all of them are available.

| Folder          | Stack                                                                                                                        | GitHub repo (private)                |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| `app_frontend/` | Flutter (Dart SDK ^3.7), provider, http, google_sign_in, flutter_dotenv                                                      | `goawayofficial/goaway_app_frontend` |
| `web_frontend/` | React 19, TypeScript (strict), Vite 8, react-router-dom 7, CSS Modules — web version of the app, mirrors it screen by screen | `goawayofficial/goaway_web_frontend` |
| `backend/`      | Spring Boot 4.1, Java 17, Maven (`mvnw`), PostgreSQL 17 (Docker Compose locally, Testcontainers in tests), Flyway            | `goawayofficial/goaway_backend`      |

This parent folder is NOT a git repo — run git/gh commands inside `app_frontend/`, `web_frontend/` or `backend/`.

## GitHub

- Account: `goawayofficial` (the user's 2nd account; the global git identity `02priyeshraj` is their 1st account — don't use it here).
- Each repo has local git config `user.name=goawayofficial`, `user.email=teamofficialgoaway@gmail.com`.
- `gh` CLI is logged in as `goawayofficial` with ADMIN on all repos.
- Default branch: `develop`.

## Branching (all repos)

| Branch                                    | Environment    | Rules                                                                                                          |
| ----------------------------------------- | -------------- | -------------------------------------------------------------------------------------------------------------- |
| `feature/*`, `fix/*`, `chore/*`, `docs/*` | Local          | Short-lived; branch from latest `develop`                                                                      |
| `develop` (default)                       | Test / staging | Only via PR from a work branch; CI must pass                                                                   |
| `main`                                    | Production     | Only via release PR from `develop` (after acceptance testing on test), or `hotfix/*` PR; tag releases `vX.Y.Z` |

- Never commit or push directly to `develop` or `main`.
- After a hotfix merges to `main`, merge `main` back into `develop`.
- Commit messages: Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`, `test:`, `refactor:`).
- Squash-merge work PRs into `develop`; use a merge commit for `develop → main` release PRs.
- Merge via the API — `gh pr merge --squash` produced merge commits here:
  `gh api -X PUT repos/goawayofficial/<repo>/pulls/<n>/merge -f merge_method=squash -f commit_title="<PR title> (#<n>)"`
  then verify the new commit on `develop` has a single parent.
- GitHub Free: no branch protection on private repos. The rules above are enforced by discipline, the `pre-push` hook, and the **Branch guard** workflow (fails if a commit lands on `main`/`develop` without a merged PR). See `CONTRIBUTING.md` in each repo.

## Workflow rules

- Per task: branch → code + tests → run checks locally → commit → push → open PR to `develop` → stop for the user's review.
- Show the changes and get the user's OK before any PR merge, release, or deploy.
- Never merge with failing or pending checks (`gh pr checks <n>`), even if asked — fix them first.
- Never bypass git hooks (`--no-verify`). Hooks live in `.githooks/`; enable per clone with `git config core.hooksPath .githooks`.
- `CLAUDE.md` exists in this folder and as a copy in each repo — keep all copies in sync.

## CI (GitHub Actions, every repo)

- `CI` — app: dart format check, `flutter analyze`, `flutter test`, debug APK build · web: `npm run check` · backend: `./mvnw verify`
- `Secret scan` — Gitleaks over full history
- `Branch guard` — on pushes to `main`/`develop`
- Dependabot: weekly grouped update PRs into `develop` (deps + GitHub Actions)
- Free plan = 2,000 Actions minutes/month; avoid needless re-runs.

## Secrets — never commit

- App frontend: `.env` (bundled as a Flutter asset — public config only, e.g. `GOOGLE_CLIENT_ID`, `BASE_URL`; keep `.env.example` in sync), `android/app/google-services.json`, `android/local.properties`, keystores, `key.properties`.
- Web frontend: `.env` / `.env.*` (`VITE_*` values end up in the JS bundle — public config only; keep `.env.example` in sync).
- Backend: `backend/.env` (gitignored; template `.env.example`) holds local DB passwords and is read by both Docker Compose and Spring Boot (`spring.config.import`). Deployed environments use env vars. Never put secrets in `application.properties`. Never print `.env` values in chat.

## Commands

- App frontend: `flutter pub get`, `flutter analyze`, `flutter test`, `flutter run`
- Web frontend (Node 22.12+, `.nvmrc` = 24): `npm install`, `npm run dev`, `npm run check` (typecheck + lint + format check + build — run before every PR)
- Backend (JDK 17+, Docker Desktop): `docker compose up -d` (PostgreSQL 17 on 127.0.0.1:5433), `./mvnw spring-boot:run`, `./mvnw verify` (tests start their own PostgreSQL via Testcontainers)
- App CI pins Flutter 3.47.7 (matches local); update `.github/workflows/ci.yml` when upgrading.

## Database (backend)

- Roles: `postgres` (container superuser, setup only) · `goaway_owner` (owns schema; Flyway only) · `goaway_app` (runtime; SELECT/INSERT/UPDATE/DELETE, no DDL). Created by `docker/postgres/init/01-create-roles.sh`, reused by tests; `DatabaseSetupTests` verifies it.
- Schema changes only via new Flyway migrations `src/main/resources/db/migration/V<n>__<desc>.sql`; never edit a merged migration.
- Backend runs in UTC (`BackendApplication.main` + surefire `-Duser.timezone=UTC`): Windows reports the legacy zone `Asia/Calcutta`, which PostgreSQL 17 (Debian 13 image) rejects.
- Keep the `postgres:` image tag in `compose.yaml` and `PostgresIntegrationTest` in sync.

## Deployment

Not set up yet for any project.

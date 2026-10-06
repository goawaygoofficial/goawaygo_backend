<!-- Copy of Goaway/CLAUDE.md (workspace root, shared by both repos). Keep the two copies in sync. -->

# Goaway

Workspace with two separate git repos. Start Claude sessions in this `Goaway` folder so both are available.

| Folder | Stack | GitHub repo (private) |
|---|---|---|
| `app_frontend/` | Flutter (Dart SDK ^3.7), provider, http, google_sign_in, flutter_dotenv | `goawayofficial/goaway_app_frontend` |
| `backend/` | Spring Boot 4.1, Java 17, Maven (`mvnw`), MySQL, spring-session-jdbc | `goawayofficial/goaway_backend` |

This parent folder is NOT a git repo — run git/gh commands inside `app_frontend/` or `backend/`.

## GitHub
- Account: `goawayofficial` (the user's 2nd account; the global git identity `02priyeshraj` is their 1st account — don't use it here).
- Each repo has local git config `user.name=goawayofficial`, `user.email=teamofficialgoaway@gmail.com`.
- `gh` CLI is logged in as `goawayofficial` with ADMIN on both repos.
- Default branch: `develop`.

## Branching (both repos)
| Branch | Environment | Rules |
|---|---|---|
| `feature/*`, `fix/*`, `chore/*`, `docs/*` | Local | Short-lived; branch from latest `develop` |
| `develop` (default) | Test / staging | Only via PR from a work branch; CI must pass |
| `main` | Production | Only via release PR from `develop` (after acceptance testing on test), or `hotfix/*` PR; tag releases `vX.Y.Z` |

- Never commit or push directly to `develop` or `main`.
- After a hotfix merges to `main`, merge `main` back into `develop`.
- Commit messages: Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`, `test:`, `refactor:`).
- Squash-merge work PRs into `develop`; use a merge commit for `develop → main` release PRs.

## Workflow rules
- Per task: branch → code + tests → run checks locally → commit → push → open PR to `develop` → stop for the user's review.
- Show the changes and get the user's OK before any PR merge, release, or deploy.

## Secrets — never commit
- Frontend: `.env` (bundled as a Flutter asset — public config only, e.g. `GOOGLE_CLIENT_ID`, `BASE_URL`; keep `.env.example` in sync), `android/app/google-services.json`, `android/local.properties`, keystores, `key.properties`.
- Backend: DB passwords and other secrets go in env vars or `application-local/dev/prod.properties` / `.env` (all gitignored) — never in `application.properties`.

## Commands
- Frontend: `flutter pub get`, `flutter analyze`, `flutter test`, `flutter run`
- Backend: `./mvnw spring-boot:run`, `./mvnw test`

## Deployment
Not set up yet for either project.

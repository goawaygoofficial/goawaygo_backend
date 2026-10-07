<!-- Copy of Goaway/CLAUDE.md (workspace root, shared by all repos). Keep the copies in sync. -->

# Goaway

Workspace with three separate git repos. Start Claude sessions in this `Goaway` folder so all of them are available.

| Folder | Stack | GitHub repo (private) |
|---|---|---|
| `app_frontend/` | Flutter (Dart SDK ^3.7), provider, http, google_sign_in, flutter_dotenv | `goawayofficial/goaway_app_frontend` |
| `web_frontend/` | React 19, TypeScript (strict), Vite 8, react-router-dom 7, CSS Modules — web version of the app, mirrors it screen by screen | `goawayofficial/goaway_web_frontend` |
| `backend/` | Spring Boot 4.1, Java 17, Maven (`mvnw`), MySQL, spring-session-jdbc | `goawayofficial/goaway_backend` |

This parent folder is NOT a git repo — run git/gh commands inside `app_frontend/`, `web_frontend/` or `backend/`.

## GitHub
- Account: `goawayofficial` (the user's 2nd account; the global git identity `02priyeshraj` is their 1st account — don't use it here).
- Each repo has local git config `user.name=goawayofficial`, `user.email=teamofficialgoaway@gmail.com`.
- `gh` CLI is logged in as `goawayofficial` with ADMIN on all repos.
- Default branch: `develop`.

## Branching (all repos)
| Branch | Environment | Rules |
|---|---|---|
| `feature/*`, `fix/*`, `chore/*`, `docs/*` | Local | Short-lived; branch from latest `develop` |
| `develop` (default) | Test / staging | Only via PR from a work branch; CI must pass |
| `main` | Production | Only via release PR from `develop` (after acceptance testing on test), or `hotfix/*` PR; tag releases `vX.Y.Z` |

- Never commit or push directly to `develop` or `main`.
- After a hotfix merges to `main`, merge `main` back into `develop`.
- Commit messages: Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`, `test:`, `refactor:`).
- Squash-merge work PRs into `develop` (`gh pr merge --squash`, then verify the result is a single non-merge commit); use a merge commit for `develop → main` release PRs.

## Workflow rules
- Per task: branch → code + tests → run checks locally → commit → push → open PR to `develop` → stop for the user's review.
- Show the changes and get the user's OK before any PR merge, release, or deploy.
- `CLAUDE.md` exists in this folder and as a copy in each repo — keep all copies in sync.

## Secrets — never commit
- App frontend: `.env` (bundled as a Flutter asset — public config only, e.g. `GOOGLE_CLIENT_ID`, `BASE_URL`; keep `.env.example` in sync), `android/app/google-services.json`, `android/local.properties`, keystores, `key.properties`.
- Web frontend: `.env` / `.env.*` (`VITE_*` values end up in the JS bundle — public config only; keep `.env.example` in sync).
- Backend: DB passwords and other secrets go in env vars or `application-local/dev/prod.properties` / `.env` (all gitignored) — never in `application.properties`.

## Commands
- App frontend: `flutter pub get`, `flutter analyze`, `flutter test`, `flutter run`
- Web frontend (Node 22.12+, `.nvmrc` = 24): `npm install`, `npm run dev`, `npm run check` (typecheck + lint + format check + build — run before every PR)
- Backend: `./mvnw spring-boot:run`, `./mvnw test`

## Deployment
Not set up yet for any project.

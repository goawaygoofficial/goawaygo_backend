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
- Default branch: `main`.

## Workflow rules
- Show the changes and get the user's OK before any commit, push, PR merge, or deploy.

## Secrets — never commit
- Frontend: `.env` (bundled as a Flutter asset — public config only, e.g. `GOOGLE_CLIENT_ID`, `BASE_URL`; keep `.env.example` in sync), `android/app/google-services.json`, `android/local.properties`, keystores, `key.properties`.
- Backend: DB passwords and other secrets go in env vars or `application-local/dev/prod.properties` / `.env` (all gitignored) — never in `application.properties`.

## Commands
- Frontend: `flutter pub get`, `flutter analyze`, `flutter test`, `flutter run`
- Backend: `./mvnw spring-boot:run`, `./mvnw test`

## Deployment
Not set up yet for either project.

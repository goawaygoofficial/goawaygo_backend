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

- App frontend: `.env` (bundled as a Flutter asset — public config only: `BASE_URL`, `GOOGLE_WEB_CLIENT_ID`, `GOOGLE_IOS_CLIENT_ID`; keep `.env.example` in sync), `android/app/google-services.json` (if Firebase is ever added), `android/local.properties`, keystores, `key.properties`.
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

## Authentication (decided: short JWT + rotating refresh token)

- Access token: ES256 JWT, **20 min**, `Authorization: Bearer`; not stored. Keys: `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` (base64 DER) in `backend/.env`; each environment has its own pair; never print or commit them.
- Refresh token: random, **30 days**, stored only as SHA-256 in `refresh_tokens`; rotated on every use; reuse of a rotated token revokes the whole family.
- Mobile sends `X-Client-Type: mobile` → refresh token in JSON body (store in Keychain/Keystore). Web → refresh token only in `HttpOnly; Secure; SameSite=Strict` cookie `goaway_refresh` on `/api/auth`; keep access token in memory, never `localStorage`.
- Profile: `GET /api/users/me` (own profile only). There is no `GET /api/auth/users/{id}`: the old frontend code that calls it must be replaced.
- Errors: always `{status, error, message[, fieldErrors]}`; clients branch on `error`.
- Never trust identity from the client; derive the user from the access token (`jwt.getSubject()`).
- Password reset / email verification: single-use emailed links (`user_action_tokens`, SHA-256 hashes; reset 30 min, verify 24 h) opening the website (`/reset-password`, `/verify-email`). Forgot-password must not reveal whether an account exists (same 202 body; email sent after commit in the background). Never accept `{email, newPassword}`.
- Email: SMTP only (`EmailSender`/`EmailOutbox`). Local: Mailpit from `docker compose` (inbox http://localhost:8025). Deployed: Resend over SMTP (`MAIL_*` env vars; the API key is a secret). Never log email bodies (they contain one-time links).
- Rate limits (Bucket4j, in-memory per instance): per IP on public `/api/auth/*`, per email on signin/forgot-password; 429 + `Retry-After`. Tests disable it in `PostgresIntegrationTest`.

## Google Sign-In

- App ID (Android `applicationId`/`namespace`, iOS/macOS bundle ID): **`com.goawayofficial.app`**. Permanent once on Google Play.
- Google Cloud project `goaway-483709` (number `1018541563002`), consent screen in **Testing** (only listed test users can sign in). OAuth clients (IDs are public; never handle client secrets):
  - Web `1018541563002-sft2jld5f3n6lnenvt0pk8hknk7hfq9n.apps.googleusercontent.com`: website, and the ID-token audience (`serverClientId`) for the mobile app. Origins: `http://localhost:5173`, `http://localhost`.
  - Android `1018541563002-s8eo8sn5e7dt9kaav6rm0mhgea6tgir9.apps.googleusercontent.com`: package + the user's debug SHA-1 `79:E2:F5:3A:AD:5E:E7:84:08:30:CE:07:1C:9A:F0:EC:FB:90:2F:C8`. Release / Play App Signing SHA-1 needs its own Android client later.
  - iOS `1018541563002-ss0dur7e8d4l9c2lb2o89edgouqrd2fv.apps.googleusercontent.com`
- Backend must verify Google ID tokens (signature via Google JWKs, `iss`, `exp`, `aud` ∈ configured client IDs, `email_verified`) and never trust user details sent alongside them.
- Mobile sends the ID token to `/api/auth/google-signin`. The website uses its own styled button + Google code client (popup) and sends the one-time code to `/api/auth/google-signin/code`; the backend exchanges it with `GOOGLE_CLIENT_SECRET` (backend `.env` only, entered by the user; never ask for it in chat, never print it).

## Deployment

Not set up yet for any project.

<!-- Copy of Goaway/CLAUDE.md (workspace root, shared by all repos). Keep the copies in sync. -->

# GoAwayGo

Workspace with three separate git repos. Start Claude sessions in this folder (on disk: `Dev/Goaway`) so all of them are available.

| Folder          | Stack                                                                                                                        | GitHub repo (private)                    |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| `app_frontend/` | Flutter 3.47 (Dart ^3.13), provider, http, google_sign_in, flutter_secure_storage, Sentry, PostHog                           | `goawaygoofficial/goawaygo_app_frontend` |
| `web_frontend/` | React 19, TypeScript (strict), Vite 8, react-router-dom 7, CSS Modules — web version of the app, mirrors it screen by screen | `goawaygoofficial/goawaygo_web_frontend` |
| `backend/`      | Spring Boot 4.1, Java 17, Maven (`mvnw`), PostgreSQL 17 (Docker Compose locally, Testcontainers in tests), Flyway, Sentry    | `goawaygoofficial/goawaygo_backend`      |

This parent folder is NOT a git repo — run git/gh commands inside `app_frontend/`, `web_frontend/` or `backend/`.

## GitHub

- Account: `goawaygoofficial` (the user's 2nd account; the global git identity `02priyeshraj` is their 1st account — don't use it here).
- Each repo has local git config `user.name=goawaygoofficial` and GitHub's private no-reply `user.email` (the repos are public, decided 2026-11-11: the team email stays out of commits).
- `gh` CLI is logged in as `goawaygoofficial` with ADMIN on all repos.
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
  `gh api -X PUT repos/goawaygoofficial/<repo>/pulls/<n>/merge -f merge_method=squash -f commit_title="<PR title> (#<n>)"`
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

- App frontend: `.env` (bundled as a Flutter asset — public config only: `BASE_URL`, `WEBSITE_URL`, `GOOGLE_WEB_CLIENT_ID`, `GOOGLE_IOS_CLIENT_ID`, `SENTRY_DSN`, `POSTHOG_KEY`; keep `.env.example` in sync), `android/app/google-services.json` (if Firebase is ever added), `android/local.properties`, keystores, `key.properties`.
- Web frontend: `.env` / `.env.*` (`VITE_*` values end up in the JS bundle — public config only; keep `.env.example` in sync).
- Backend: `backend/.env` (gitignored; template `.env.example`) holds local DB passwords and is read by both Docker Compose and Spring Boot (`spring.config.import`). Deployed environments use env vars. Never put secrets in `application.properties`. Never print `.env` values in chat.

## Commands

- App frontend: `flutter pub get`, `flutter analyze`, `flutter test`, `flutter run`
- Web frontend (Node 22.12+, `.nvmrc` = 24): `npm install`, `npm run dev`, `npm run check` (typecheck + lint + format check + build — run before every PR)
- Backend (JDK 17+, Docker Desktop): `docker compose up -d` (PostgreSQL 17 on 127.0.0.1:5433), `./mvnw spring-boot:run`, `./mvnw verify` (tests start their own PostgreSQL via Testcontainers)
- App CI pins Flutter 3.47.7 (matches local); update `.github/workflows/ci.yml` when upgrading.

## Database (backend)

- Roles: `postgres` (container superuser, setup only) · `goawaygo_owner` (owns schema; Flyway only) · `goawaygo_app` (runtime; SELECT/INSERT/UPDATE/DELETE, no DDL). Created by `docker/postgres/init/01-create-roles.sh`, reused by tests; `DatabaseSetupTests` verifies it.
- Schema changes only via new Flyway migrations `src/main/resources/db/migration/V<n>__<desc>.sql`; never edit a merged migration.
- Backend runs in UTC (`BackendApplication.main` + surefire `-Duser.timezone=UTC`): Windows reports the legacy zone `Asia/Calcutta`, which PostgreSQL 17 (Debian 13 image) rejects.
- Keep the `postgres:` image tag in `compose.yaml` and `PostgresIntegrationTest` in sync.

## Authentication (decided: short JWT + rotating refresh token)

- Access token: ES256 JWT, **20 min**, `Authorization: Bearer`; not stored. Keys: `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` (base64 DER) in `backend/.env`; each environment has its own pair; never print or commit them.
- Refresh token: random, **30 days**, stored only as SHA-256 in `refresh_tokens`; rotated on every use; reuse of a rotated token revokes the whole family.
- Mobile sends `X-Client-Type: mobile` → refresh token in JSON body (store in Keychain/Keystore). Web → refresh token only in `HttpOnly; Secure; SameSite=Strict` cookie `goawaygo_refresh` on `/api/auth`; keep access token in memory, never `localStorage`.
- Profile: `GET /api/users/me` (own profile only). There is no `GET /api/auth/users/{id}`: the old frontend code that calls it must be replaced.
- Errors: always `{status, error, message[, fieldErrors]}`; clients branch on `error`.
- Never trust identity from the client; derive the user from the access token (`jwt.getSubject()`).
- Password reset / email verification: single-use emailed links (`user_action_tokens`, SHA-256 hashes; reset 30 min, verify 24 h) opening the website (`/reset-password`, `/verify-email`). Forgot-password must not reveal whether an account exists (same 202 body; email sent after commit in the background). Never accept `{email, newPassword}`.
- Email: SMTP only (`EmailSender`/`EmailOutbox`). Local: Mailpit from `docker compose` (inbox http://localhost:8025). Deployed: Resend over SMTP (`MAIL_*` env vars; the API key is a secret). Never log email bodies (they contain one-time links).
- Rate limits (Bucket4j, in-memory per instance): per IP on public `/api/auth/*`, per email on signin/forgot-password; 429 + `Retry-After`. Tests disable it in `PostgresIntegrationTest`.

## Google Sign-In

- App ID (Android `applicationId`/`namespace`, iOS/macOS bundle ID): **`com.goawaygo.app`**. Permanent once on Google Play.
- App minimum OS: **Android 8.0** (API 26, `minSdk = 26` in `android/app/build.gradle.kts`), **iOS 15.0**, macOS 12.0, required by `sentry_flutter` 10. Don't lower them.
- Google Cloud project `goaway-483709` (number `1018541563002`), consent screen in **Testing** (only listed test users can sign in). OAuth clients (IDs are public; never handle client secrets):
  - Web `1018541563002-sft2jld5f3n6lnenvt0pk8hknk7hfq9n.apps.googleusercontent.com`: website, and the ID-token audience (`serverClientId`) for the mobile app. Origins: `http://localhost:5173`, `http://localhost`.
  - Android `1018541563002-s8eo8sn5e7dt9kaav6rm0mhgea6tgir9.apps.googleusercontent.com`: package + the user's debug SHA-1 `79:E2:F5:3A:AD:5E:E7:84:08:30:CE:07:1C:9A:F0:EC:FB:90:2F:C8`. Release / Play App Signing SHA-1 needs its own Android client later.
  - iOS `1018541563002-ss0dur7e8d4l9c2lb2o89edgouqrd2fv.apps.googleusercontent.com`
- Backend must verify Google ID tokens (signature via Google JWKs, `iss`, `exp`, `aud` ∈ configured client IDs, `email_verified`) and never trust user details sent alongside them.
- Mobile sends the ID token to `/api/auth/google-signin`. The website uses its own styled button + Google code client (popup) and sends the one-time code to `/api/auth/google-signin/code`; the backend exchanges it with `GOOGLE_CLIENT_SECRET` (backend `.env` only, entered by the user; never ask for it in chat, never print it).

## Discover, Wanderbook, Book, the trip planner, notifications and Me (4.1-4.6)

- **How we build:** MVP rounds. MVP 1 builds every Phase 4 sub-phase; fixes, changes and new ideas go into later MVPs (see `ROADMAP.md`).
- Outside services go through the backend's API toolkit (`com.goawaygo.backend.external`) with daily limits below free tiers: Gemini (destination cards, trip ideas), Geoapify (places, photo location → place name), Wikimedia and Unsplash (photos), OpenAI moderation (Wanderbook), Travelpayouts (Book: Aviasales prices, affiliate links). Keys: Secret Manager `goawaygo-api-<name>` + repo variable `EXTERNAL_API_PROVIDERS`. Never store Discover's AI answers or spot photos (asked fresh on every page open); a trip plan is the user's own content once saved (`trip_plans`).
- Wanderbook photos: originals (never resized), only location data removed by the backend; exact coordinates never stored. Stored in R2 `goawaygo-media` (`media.goawaygo.com`), key via `scripts/gcp-secrets.sh <project> r2` + repo variable `WANDERBOOK_PHOTOS=true`. Uploads stop at 9.5 GB.
- Moderation fails closed: if OpenAI can't check, nothing is posted. Reports email `team@goawaygo.com`; admins (`users.is_admin`, set by hand in Neon) review on the website's `/admin/reports`.
- Viewing Wanderbook is public (rate limited per IP); profiles/posts use random 12-character public ids, never user ids.
- Trip planner: Gemini (Lite models first) writes a day-by-day plan with rough costs (whole group, flights never included); signed-in users only, saved in My Trips, one day can be rewritten, a plan can be shared read-only by link after an OpenAI moderation check.
- Notifications: saved for the bell (90 days) on the app and website; pushed to Android through Firebase Cloud Messaging with the backend's own service account (no key); a switch per type; trip reminders run from a daily GitHub Actions call. The app's Firebase IDs are public values in `env/staging.env` (never commit `google-services.json`).
- Languages (4.7): en, hi, es, fr, de, ar (RTL), ja. Backend text the user sees goes through `T.t(lang, "English sentence {0}", args)` with translations in `src/main/resources/i18n/<code>.json` (the English sentence is the key; `LanguageTests` checks every file has the same keys and placeholders). New user-facing backend text needs all six translations. Requests carry `Accept-Language`; `users.language` is for pushes and emails. Legal pages stay English.
- Me: the account name and photo are also the Wanderbook name and photo. Changing the password or email and deleting the account first confirm it's you (password, or Google without one). Deleting hides the account for 30 days (signing in keeps it), then the daily GitHub Actions job deletes its R2 files and the account (every table cascades); queries that show people to others must skip `users.deletion_requested_at IS NOT NULL`. Phone sign-in waits for MVP 2 (SMS costs money).
- Book: affiliate model, travellers book and pay on the partner's site. Flights show Aviasales' recent prices (labelled "may change"); stays and cars are partner links. Every partner page becomes our tracked link via Travelpayouts (`goawaygo.book.*`); the app only shows brands that allow apps. Never load partner scripts (e.g. Travelpayouts Drive) on our site.

## Deployment

Domain `goawaygo.com` (Cloudflare Registrar + DNS). Website and API must stay on the same registrable domain (the refresh cookie is `SameSite=Strict`).

- Pre-launch (alpha/beta, all free): backend on Google Cloud Run `asia-southeast1` (project `goawaygo-staging`, service `goawaygo-backend`, `api.goawaygo.com`); database Neon PostgreSQL 17 (AWS Singapore); website on Cloudflare Pages (`staging.goawaygo.com`); email Resend; Android testers via Firebase App Distribution.
- At launch (Phase 5): database moves to Google Cloud SQL; Play Console then. Company not registered yet.
- Backend deploys automatically after CI passes on `develop` (`.github/workflows/deploy-staging.yml`, keyless Workload Identity Federation). Secrets live only in Google Secret Manager; setup scripts and steps in `backend/docs/deployment.md`.
- Cloud Run: min 0 instances (cold start ~7–10 s after idle), max 1 (in-memory rate limits), request-based billing (CPU only during requests; emails sent inside the request). Health: `GET /actuator/health` (no DB check, so Neon can sleep).
- Google free trial ends 90 days after sign-up: upgrade billing before then or the project stops.
- After a merge: backend and website deploy themselves; Android builds are released by hand with `app_frontend/scripts/release-android.sh` (upload key on the release PC only). Step by step: `RELEASING.md` (copy in every repo). What's next: `ROADMAP.md` (copy in every repo; tick items as they ship).
- Shared docs, identical in the workspace root and every repo (edit the root copy, then copy it into the three repos with their `<!-- Copy of ... -->` header): `CLAUDE.md`, `RELEASING.md`, `ROADMAP.md`, `FOUNDATIONS.md` (what each service is for) and `LINKS.md` (dashboards, routine, onboarding).

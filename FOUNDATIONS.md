<!-- Copy of Goaway/FOUNDATIONS.md (workspace root, shared by all repos). Keep the copies in sync. -->

# GoAwayGo — what each resource is for

Every service, library and tool GoAwayGo uses, what it does for us, and which part uses it: **B** = backend,
**W** = website, **A** = Android app. All on free tiers until launch. Links: `LINKS.md`.

## At a glance

```
 Users ──► goawaygo.com (W, Cloudflare Pages) ──────────┐
       └─► GoAwayGo app (A, APK from downloads.goawaygo.com / Firebase) ──┤
                                                          ▼
                              api.goawaygo.com (B, Google Cloud Run) ──► Neon Postgres (database)
                                     │          │            │
                                Resend (email)  Secret Manager   outside APIs (via the API toolkit):
                                                                 Gemini, Geoapify, Wikimedia, Unsplash, OpenAI
                                     │
                                     └──► Cloudflare R2 goawaygo-media ──► media.goawaygo.com (Wanderbook photos)

 Watching it: Sentry (errors) · Cloud Logging (logs) · Better Stack (uptime + status page)
              Cloudflare Web Analytics (traffic) · PostHog (product analytics, with consent)
 Guarding it: GitHub Actions CI · Gitleaks (secrets) · OSV-Scanner (vulnerable dependencies) · Dependabot
```

## 1. Running the product

| Resource                                                    | What it does for us                                                                                                                | Used by              | Free limit / cost                                                                   | Set up in                                                            |
| ----------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | -------------------- | ----------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| **Google Cloud Run** (`goawaygo-backend`, Singapore)        | Runs the backend container; serves `api.goawaygo.com`. Scales to 0 when idle, max 1 instance; billed only while handling requests. | B                    | 180k vCPU-s, 360k GiB-s, 2M requests/month free; $300 trial credit until 2027-01-14 | `backend/.github/workflows/deploy.yml`, `docs/deployment.md`         |
| **Neon Postgres 17** (AWS Singapore)                        | The database: users, sessions, email links, API cache, searches, saved places, Wanderbook. Sleeps when idle.                       | B                    | 0.5–1 GB storage, 100 compute-hours/month                                           | `backend/scripts/setup-hosted-db.sh`; tables in `db/migration`       |
| **Flyway**                                                  | Creates and updates database tables on every deploy (V1–V7), as the owner role; the app uses a limited role.                       | B                    | open source                                                                         | `backend/src/main/resources/db/migration`                            |
| **Cloudflare Pages** (`goawaygo-web-dev`, `-test`, `-prod`) | Hosts the website: one project per environment, uploaded by GitHub Actions (one build per version).                                | W                    | unlimited static requests, 500 builds/month                                         | Cloudflare dashboard; `web_frontend/docs/deployment.md`              |
| **Cloudflare R2** (`goawaygo-downloads`)                    | Stores the Android APKs and `latest.json` behind `downloads.goawaygo.com`.                                                         | A, W (download page) | 10 GB, no download fees                                                             | `app_frontend/scripts/release-android.sh`                            |
| **Firebase App Distribution** (project `goaway-483709`)     | Sends beta builds to testers; public sign-up link.                                                                                 | A                    | free                                                                                | `app_frontend/docs/release-android.md`                               |
| **Cloudflare DNS + Registrar** (`goawaygo.com`)             | The domain and all its records (web, api, downloads, status, email).                                                               | B, W, A              | at-cost domain renewal (yearly)                                                     | Cloudflare dashboard                                                 |
| **Resend**                                                  | Sends account emails (verify email, reset password) as `no-reply@goawaygo.com`.                                                    | B                    | 3,000 emails/month, 100/day                                                         | Secret `goawaygo-resend-api-key`, repo variable `MAIL_FROM`          |
| **Google Sign-In** (OAuth clients in `goaway-483709`)       | "Continue with Google" on the website and in the app.                                                                              | B, W, A              | free                                                                                | Repo variables + `goawaygo-google-client-secret`; app `env/prod.env` |

## 2. Keeping it secure

| Resource                                             | What it does for us                                                                                                                                      | Used by     | Set up in                                                   |
| ---------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------- | ----------------------------------------------------------- |
| **Google Secret Manager**                            | Holds every secret (DB passwords, JWT keys, Google client secret, Resend key, future API keys); the backend reads them at start. GitHub never sees them. | B           | `backend/scripts/gcp-secrets.sh`                            |
| **Workload Identity Federation**                     | GitHub Actions deploys to Google Cloud without any stored key.                                                                                           | B (deploys) | `backend/scripts/gcp-bootstrap.sh`                          |
| **Android upload key** (`~/.goawaygo`, this PC only) | Signs every APK so phones accept updates; its SHA-1 lets Google Sign-In trust the app. Back it up; never share it.                                       | A           | `app_frontend/scripts/create-upload-key.sh`                 |
| **Gitleaks** (pre-commit hook + CI)                  | Blocks commits and PRs that contain passwords or keys. `.gitleaks.toml` allows only the public PostHog key.                                              | B, W, A     | `.githooks/pre-commit`, `.github/workflows/secret-scan.yml` |
| **OSV-Scanner** (CI, also every Monday)              | Checks every dependency against known vulnerabilities (osv.dev); a finding fails the check.                                                              | B, W, A     | `.github/workflows/dependency-scan.yml`                     |
| **Dependabot**                                       | Weekly PRs that update libraries (grouped minor/patch; majors separate).                                                                                 | B, W, A     | `.github/dependabot.yml`                                    |
| **Rate limits** (Bucket4j, in memory)                | Limits repeated sign-in, sign-up and refresh attempts per IP and per email.                                                                              | B           | `goawaygo.rate-limit.*` in `application.properties`         |
| **Security headers**                                 | Stops the website being framed by other sites, limits referrers, turns off unused browser features.                                                      | W           | `web_frontend/public/_headers`                              |

## 3. Knowing when something breaks (Foundations 4.0a–4.0b)

| Resource                                                                                 | What it does for us                                                                                                                                 | Used by | Free limit                 | Set up in                                                                                                            |
| ---------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | -------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| **Sentry** (EU, org `goawaygo`; projects goawaygo-backend / goawaygo-web / goawaygo-app) | Catches crashes and errors with the release and environment; emails us; no personal data (scrubbers on, IPs not stored, tokens cut from URLs).      | B, W, A | 5k errors/month            | `SENTRY_DSN` (backend var), `web_frontend/src/lib/monitoring.ts`, `app_frontend/lib/core/monitoring/monitoring.dart` |
| **Google Cloud Logging + Error Reporting**                                               | Backend logs as structured JSON (severity, request ID, trace), kept 30 days; groups backend exceptions.                                             | B       | 50 GiB/month               | `logging/CloudLoggingFormatter.java`, `RequestIdFilter.java`                                                         |
| **Request IDs** (`X-Request-Id`)                                                         | One ID per API request in the response, every log line and Sentry — to follow one request end to end.                                               | B       | —                          | `logging/RequestIdFilter.java`                                                                                       |
| **Health endpoint** `/actuator/health`                                                   | "Is the backend running?" for uptime checks; never touches the database (so Neon can sleep).                                                        | B       | —                          | `application.properties` (management.*)                                                                              |
| **Better Stack**                                                                         | Checks the API and website every 3 min and `latest.json` every 30 min from 4 regions; emails on downtime; public status page `status.goawaygo.com`. | B, W, A | 10 monitors, 1 status page | Better Stack dashboard                                                                                               |

## 4. Understanding usage (Foundation 4.0d)

| Resource                               | What it does for us                                                                                                                                                                                                                                                                               | Used by | Free limit      | Set up in                                                                             |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | --------------- | ------------------------------------------------------------------------------------- |
| **Cloudflare Web Analytics**           | Page views and visitors for the website; no cookies, no consent needed.                                                                                                                                                                                                                           | W       | free            | Cloudflare Pages → Web Analytics (automatic)                                          |
| **PostHog** (EU, project 315148)       | Product analytics — only after the user taps "Allow": page views and 6 events (sign-up, sign-in, travel category, destination search, app download, beta join), anonymous. **Feature flags** are available here but switched off until we first need one (a one-line change each in web and app). | W, A    | 1M events/month | `web_frontend/src/lib/analytics.ts`, `app_frontend/lib/core/analytics/analytics.dart` |
| **Consent banner / "Privacy choices"** | Asks once (equal "No thanks" / "Allow"); the choice can be changed any time; "No thanks" deletes PostHog's ID.                                                                                                                                                                                    | W, A    | —               | `ConsentBanner.tsx`, `analytics_consent_sheet.dart`                                   |

## 5. Legal and contact (Foundation 4.0c)

| Resource                                         | What it does for us                                                                                                                                                                                  | Used by | Set up in                                             |
| ------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ----------------------------------------------------- |
| **Privacy Policy, Terms of Use, Delete my data** | International (GDPR, US state laws, India DPDP, Brazil LGPD); linked from footers, sign-up and the app. Delete-my-data is also Google Play's required deletion URL. **Lawyer review before launch.** | W, A    | `web_frontend/src/features/legal`, `src/lib/legal.ts` |
| **Cloudflare Email Routing**                     | `privacy@` and `support@goawaygo.com` forward to the team Gmail (free; receive only).                                                                                                                | all     | Cloudflare → Email Routing                            |

## 6. Calling outside services safely (Foundation 4.0e)

| Resource                                          | What it does for us                                                                                                                                                                                                   | Used by | Set up in                             |
| ------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ------------------------------------- |
| **API toolkit** (`com.goawaygo.backend.external`) | The one way the backend calls outside APIs (places, photos, prices, AI from 4.1): timeouts, retries for temporary failures, circuit breaker, daily/monthly limits **below free tiers**, database cache with fallback. | B       | `docs/deployment.md` → "Outside APIs" |
| **Resilience4j**                                  | The retry and circuit-breaker engine inside the toolkit.                                                                                                                                                              | B       | `pom.xml`                             |
| **Usage counters** (`external_api_usage`)         | Calls per provider per day/month; refuses calls at the limit, so no surprise bills.                                                                                                                                   | B       | Flyway V4                             |
| **API cache** (`external_api_cache`)              | Reuses answers instead of spending quota; serves the last answer if a provider is down.                                                                                                                               | B       | Flyway V4                             |

## 7. Building and shipping

| Resource                                                        | What it does for us                                                                               | Used by |
| --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- | ------- |
| **GitHub** (org `goawaygoofficial`, private repos)              | Code, PRs, reviews; `develop` → dev, `test` → test, `main` → prod.                                | B, W, A |
| **GitHub Actions**                                              | CI on every PR (build, tests, lint, format), secret and dependency scans, and the backend deploy. | B, W, A |
| **Docker + Artifact Registry** (`goawaygo` repo, newest 3 kept) | Packages the backend and stores the images Cloud Run runs.                                        | B       |
| **Wrangler** (Cloudflare CLI, logged in on this PC)             | Uploads APKs and `latest.json` to R2 during an Android release.                                   | A       |
| **Testcontainers + Docker**                                     | Real Postgres in backend tests.                                                                   | B       |

## 8. Main frameworks and libraries

| Part    | Main pieces                                                                                                                                                  |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Backend | Java 17, Spring Boot 4.1 (web, security, JPA, validation, mail, actuator), Flyway, PostgreSQL driver, JWT (ES256), Bucket4j + Caffeine, Resilience4j, Sentry |
| Website | React 19, TypeScript, Vite, React Router, Sentry, PostHog (loaded only after consent), Roboto (bundled)                                                      |
| App     | Flutter 3.47, provider, http, google_sign_in, flutter_secure_storage, shared_preferences, url_launcher, flutter_dotenv, Sentry 10, PostHog                   |

## 9. Feature services (4.1 Discover, 4.2 Wanderbook, 4.3 Book, 4.4 Trip planner, 4.5 Notifications)

All called by the backend through the API toolkit (section 6), with daily limits below the free tiers. Keys live in
Secret Manager (`goawaygo-api-<name>`); the repo variable `EXTERNAL_API_PROVIDERS` lists the ones the deploy passes.

| Resource                                                            | What it does for us                                                                                                                    | Used by                  | Free limit / cost                                                           | Set up in                                                                        |
| ------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- | ------------------------ | --------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| **Google Gemini API** (key in "Default Gemini Project", no billing) | Writes the 9 destination cards, the hourly trip ideas and trip plans (plans: Lite models first). Cards: 3 Flash → 3.1 Lite → 3.5 Lite. | B                        | free tier (3 Flash about 20 requests/day; Lite models more); paid at launch | `docs/destination-prompts.md`, `goawaygo.discover.*`                             |
| **Geoapify**                                                        | Place search while typing, place details, and the place name for a photo's location.                                                   | B                        | 3,000 credits/day                                                           | `goawaygo.external.providers.geoapify.*`                                         |
| **Wikipedia, Wikidata, Wikimedia Commons**                          | Tourist-spot photos and their credits; confirms trip ideas are real places.                                                            | B (W, A load the photos) | free, no key (User-Agent required)                                          | `SpotPhotos`, `Ideas`                                                            |
| **Unsplash**                                                        | Backup spot photos (credited, with referral links).                                                                                    | B (W, A load the photos) | demo app: 50 requests/hour                                                  | `goawaygo.external.providers.unsplash.*`                                         |
| **OpenAI moderation** (`omni-moderation-latest`)                    | Checks every Wanderbook photo and text before it appears; nothing is posted if it can't check.                                         | B                        | free                                                                        | Secret `goawaygo-api-openai`, `ContentModeration`                                |
| **Cloudflare R2** (`goawaygo-media`)                                | Stores Wanderbook photos (originals, location removed) behind `media.goawaygo.com`.                                                    | B (W, A show them)       | 10 GB; uploads stop at 9.5 GB                                               | Secrets `goawaygo-r2-*`, repo variable `WANDERBOOK_PHOTOS`, `docs/deployment.md` |
| **Travelpayouts** (marker 798632, project 597168)                   | Recent flight prices (Aviasales Data API) and affiliate links for flights, hotels and car rentals (links API).                         | B (W, A open the links)  | free; we earn commission on bookings                                        | Secret `goawaygo-api-travelpayouts`, `goawaygo.book.*`                           |
| **KAYAK API** (sandbox)                                             | Live flight, hotel and car prices once production is approved (not used yet).                                                          | B (later)                | free sandbox; production by approval                                        | Secret `goawaygo-api-kayak`                                                      |
| **Firebase Cloud Messaging** (project `goaway-483709`)              | Push notifications to the Android app (Wanderbook activity, trip reminders, shared plans, announcements).                              | B (A receives)           | free                                                                        | Repo variable `FCM_PROJECT`; runtime account's FCM role (`docs/deployment.md`)   |
| **Admin flag** (`users.is_admin`)                                   | Who may review Wanderbook reports on `/admin/reports`; reports are also emailed to `team@goawaygo.com`.                                | B, W                     | —                                                                           | Set by hand in Neon (`docs/deployment.md`)                                       |

## Deadlines and limits to watch

| When            | What                                                                                                                                                                          |
| --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **~2027-01-04** | Upgrade Google Cloud billing before the free trial ends **2027-01-14** (otherwise the backend stops).                                                                         |
| Monthly         | Neon compute hours (100), Resend emails (3,000), Sentry errors (5k), PostHog events (1M).                                                                                     |
| Daily / storage | Gemini free requests, Geoapify 3,000 credits/day, Unsplash 50/hour; R2 `goawaygo-media` 10 GB (Wanderbook uploads stop at 9.5 GB).                                            |
| Before launch   | Lawyer review of legal pages; company registration; Play Console + Android developer verification (rule reaching India in 2027); production on `goawaygo.com` with Cloud SQL. |
| DPDP            | India's substantive obligations apply from **13 May 2027**.                                                                                                                   |
| Yearly          | `goawaygo.com` renewal (Cloudflare Registrar).                                                                                                                                |

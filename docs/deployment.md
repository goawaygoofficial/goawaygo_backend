# Deployment

How the GoAwayGo backend runs outside a developer machine. Pre-launch (alpha/beta) everything here is free; at
launch the database moves to Google Cloud SQL (Phase 5).

## Architecture

| Part             | Service                                                  | Address                                                                    |
| ---------------- | -------------------------------------------------------- | -------------------------------------------------------------------------- |
| Backend          | Google Cloud Run, `asia-southeast1` (Singapore)          | `api.goawaygo.com` (prod), `api-test.goawaygo.com`, `api-dev.goawaygo.com` |
| Database         | Neon PostgreSQL 17, AWS Singapore (free plan)            | private (TLS only)                                                         |
| Secrets          | Google Secret Manager                                    | read by the backend only                                                   |
| Container images | Google Artifact Registry `goawaygo` (the newest 10 kept) | —                                                                          |
| DNS              | Cloudflare (`goawaygo.com`)                              | —                                                                          |
| Email            | Resend over SMTP                                         | `no-reply@goawaygo.com`                                                    |

Why Singapore: Cloud Run can attach a custom domain directly there (not in Mumbai, which would need a paid load
balancer), and Neon has a Singapore region next to it. Users in India are ~50–70 ms away.

```
merge into develop ─► CI (tests) ─► "Release": version vX.Y.Z ─► image …/backend:vX.Y.Z (built once) ─► dev
                                                                         │
                     controller.yaml (5.4): the same image ──────────────┴─► test ─► prod
                                    Flyway migrates each environment's database when its service starts
```

## How a deploy works

`.github/workflows/release.yml` runs after CI passes on `develop` (or by hand: Actions → Release → Run workflow). It
makes the version and its GitHub Release, builds the [Dockerfile](../Dockerfile) once and pushes
`…/goawaygo/backend:vX.Y.Z`, then calls `.github/workflows/deploy.yml` for **dev**. `deploy.yml` deploys one version
to one environment (dev, test or prod); test and prod are deployed by `controller.yaml` (5.4). A deploy:

1. Gets short-lived Google credentials through Workload Identity Federation. **No Google key is stored in GitHub.**
2. Checks that the version's image exists, and deploys it to that environment's Cloud Run service with its plain
   settings as environment variables and its secrets from Secret Manager.
3. Checks that `GET /api/users/me` answers 401 (the app is up and security is on).

On start the backend runs any new Flyway migrations as `goawaygo_owner`, then serves requests as `goawaygo_app`.

Cloud Run settings and why:

- `min-instances 0`: free while nobody uses it. The first request after ~15 idle minutes waits for a cold start
  (~7–10 s for Spring Boot); later requests are fast.
- `max-instances 1`: rate limits are kept in memory, so they only hold with a single instance.
- `cpu-throttling` (request-based billing): CPU is given, and billed, only while a request is being handled. An
  instance kept up between uptime checks costs nothing. So nothing may run after the response: emails are sent inside
  the request (`goawaygo.mail.async=false`), and forgot-password always takes at least 1.5 s so its timing doesn't
  reveal whether the account exists.
- `cpu-boost`: extra CPU during startup, shorter cold starts.

## Rollback

Every deploy is a Cloud Run revision. To go back to the previous one:

```bash
gcloud run revisions list --service goawaygo-backend --region asia-southeast1
```

```bash
gcloud run services update-traffic goawaygo-backend --region asia-southeast1 --to-revisions REVISION_NAME=100
```

Database migrations are not rolled back automatically. Migrations should be additive (new columns/tables) so the
previous revision keeps working.

## Environments (MVP 2, 5.3)

Naming: **staging** means non-production (dev and test), **prod** means production, everywhere. What ran before the
split became **prod**: same service, database, secrets and `api.goawaygo.com`, so the beta app and the testers' accounts
carry on. dev and test are new and start empty (Flyway creates their tables).

- Neon: project `goawaygo-prod` (renamed from the first `goawaygo-staging`) holds `goawaygo`; project
  `goawaygo-staging` holds `goawaygo_dev` and `goawaygo_test`.
- Google Cloud: project `goawaygo-staging` runs dev and test and keeps the image registry (images are built once);
  production runs in its own project `goawaygo-prod` (moved 2026-11-12, same address).

|                   | dev                                       | test                         | prod                              |
| ----------------- | ----------------------------------------- | ---------------------------- | --------------------------------- |
| Gets              | every version (Release)                   | a version from dev (5.4)     | a version from test (5.4)         |
| Approval          | none                                      | the owner                    | the owner                         |
| Cloud Run service | `goawaygo-backend-dev`                    | `goawaygo-backend-test`      | `goawaygo-backend`                |
| API               | `api-dev.goawaygo.com`                    | `api-test.goawaygo.com`      | `api.goawaygo.com`                |
| Website           | `dev.goawaygo.com`                        | `test.goawaygo.com`          | `goawaygo.com` (at launch)        |
| Pages project     | `goawaygo-web-dev`                        | `goawaygo-web-test`          | `goawaygo-web-prod`               |
| Google Cloud      | `goawaygo-staging`                        | `goawaygo-staging`           | `goawaygo-prod`                   |
| Neon project      | `goawaygo-staging`                        | `goawaygo-staging`           | `goawaygo-prod`                   |
| Database          | `goawaygo_dev`                            | `goawaygo_test`              | `goawaygo`                        |
| Roles             | `goawaygo_dev_owner`/`_app`               | `goawaygo_test_owner`/`_app` | `goawaygo_owner`/`_app`           |
| Own secrets       | `goawaygo-dev-db-*`, `goawaygo-dev-jwt-*` | `goawaygo-test-…`            | `goawaygo-db-*`, `goawaygo-jwt-*` |

Shared by all three: the Google client secret, Resend, R2 and the outside API keys (the AI's free daily quota too),
Firebase. Photos (R2) are on in prod only for now: dev and test refuse photo uploads (`WANDERBOOK_PHOTOS` unset).

Each environment is a **GitHub environment** of the same name in the backend and website repos, holding what differs:

| Variable (backend)                        | dev                                        | test                            | prod                                            |
| ----------------------------------------- | ------------------------------------------ | ------------------------------- | ----------------------------------------------- |
| `SERVICE`                                 | `goawaygo-backend-dev`                     | `goawaygo-backend-test`         | `goawaygo-backend`                              |
| `SECRET_PREFIX`                           | `goawaygo-dev`                             | `goawaygo-test`                 | `goawaygo`                                      |
| `DB_NAME`, `DB_OWNER_USER`, `DB_APP_USER` | `goawaygo_dev`, `…_dev_owner`, `…_dev_app` | `goawaygo_test`, `…`            | (repository defaults)                           |
| `DB_HOST`                                 | the dev/test database host                 | the dev/test database host      | (repository variable)                           |
| `CORS_ALLOWED_ORIGINS`, `WEB_BASE_URL`    | `https://dev.goawaygo.com`                 | `https://test.goawaygo.com`     | `https://goawaygo.com` (+ staging until launch) |
| `API_BASE_URL`                            | `https://api-dev.goawaygo.com`             | `https://api-test.goawaygo.com` | `https://api.goawaygo.com`                      |
| `SENTRY_ENVIRONMENT`                      | `dev`                                      | `test`                          | `production`                                    |

Website environments hold `PAGES_PROJECT` and `WEB_URL`. Anything not set in an environment falls back to the
repository variable of the same name.

Setting up dev or test (once each; `<env>` is `dev` or `test`):

1. Database: `GOAWAYGO_ENV=<env> scripts/setup-hosted-db.sh goawaygo-staging` (asks for the host and admin
   password; creates `goawaygo_<env>` and its roles, stores their passwords as `goawaygo-<env>-db-*-password`).
2. Login keys: `GOAWAYGO_ENV=<env> scripts/gcp-secrets.sh goawaygo-staging jwt`, then
   `GOAWAYGO_ENV=<env> scripts/gcp-secrets.sh goawaygo-staging access`.
3. The GitHub environment `<env>` with the variables above.
4. After its first deploy: make it public, map `api-<env>.goawaygo.com` (steps 5 and 6 below, with the service
   `goawaygo-backend-<env>`).

### Production in its own project (5.3 part C, done 2026-11-12)

prod moved from `goawaygo-staging` to the Google Cloud project `goawaygo-prod`, so production has its own
permissions, logs and secrets. The database (Neon `goawaygo-prod`), the image and `api.goawaygo.com` stay the same.
Images are still built once, in `goawaygo-staging` (repository variable `IMAGE_PROJECT_ID=goawaygo-staging`); prod's
deployer and Cloud Run may read them.

1. Create the project `goawaygo-prod` and link the billing account (done 2026-11-11).
2. Set it up, reading images from `goawaygo-staging` and sending pushes through Firebase:

   ```bash
   IMAGES_FROM=goawaygo-staging FCM_PROJECT=goaway-483709 \
     GITHUB_REPO_ID="$(gh api repos/goawaygoofficial/goawaygo_backend --jq .id)" scripts/gcp-bootstrap.sh goawaygo-prod
   ```

3. Copy production's secrets (same values, so nobody is signed out; never printed):
   `scripts/gcp-copy-secrets.sh goawaygo-staging goawaygo-prod`.
4. The GitHub environment **prod** gets the four values the setup prints: `GCP_PROJECT_ID`, `GCP_WIF_PROVIDER`,
   `GCP_DEPLOY_SA`, `GCP_RUNTIME_SA` (dev and test keep the repository variables, `goawaygo-staging`).
5. The next prod deploy (Controller, after a merge into `main`) creates `goawaygo-backend` in `goawaygo-prod`. Make it
   public (step 5 below with `--project goawaygo-prod`) and check it on its `run.app` address.
6. Move the address (the app is offline until Google issues the certificate, ~15–60 minutes; warn the testers):

   ```bash
   gcloud beta run domain-mappings delete --domain api.goawaygo.com --region asia-southeast1 --project goawaygo-staging
   gcloud beta run domain-mappings create --service goawaygo-backend --domain api.goawaygo.com --region asia-southeast1 --project goawaygo-prod
   ```

   The Cloudflare record (CNAME `api` → `ghs.googlehosted.com`) stays.

7. After a week without problems, a project owner deletes the old service `goawaygo-backend` and prod's secrets
   (`goawaygo-db-*`, `goawaygo-jwt-*`) in `goawaygo-staging`. The shared keys stay there for dev and test; change a
   shared key in both projects from then on.

## One-time setup (per environment)

Done once by a project owner. Commands run from the `backend` folder in Git Bash. Nothing below prints a secret.

### 1. Google Cloud project

1. Create the project (e.g. `goawaygo-staging`) with billing (free trial) and a budget alert
   (Billing → Budgets & alerts, ₹100, alerts at 50/90/100%).
2. Install the gcloud CLI and sign in: `gcloud auth login`.
3. Run:

   ```bash
   GITHUB_REPO_ID="$(gh api repos/goawaygoofficial/goawaygo_backend --jq .id)" scripts/gcp-bootstrap.sh goawaygo-staging
   ```

   Deploys are tied to the repository's numeric ID, so renaming the repo or the GitHub account doesn't break them.

   It enables the APIs, creates the image repository, the two service accounts and Workload Identity Federation,
   and prints the GitHub variables for step 4.

### 2. Database (Neon)

1. In Neon create the project: PostgreSQL 17, region AWS Asia Pacific (Singapore). `goawaygo-prod` for prod,
   `goawaygo-staging` for dev and test (Environments above).
2. Run (asks for the host and the admin password; Neon → Dashboard → Connect, use the host **without** `-pooler`):

   ```bash
   scripts/setup-hosted-db.sh goawaygo-staging
   ```

   It creates the `goawaygo` database with the `goawaygo_owner` / `goawaygo_app` roles (same script as local
   development) and stores their new random passwords in Secret Manager.

### 3. Secrets

```bash
scripts/gcp-secrets.sh goawaygo-staging jwt
```

```bash
scripts/gcp-secrets.sh goawaygo-staging google
```

```bash
scripts/gcp-secrets.sh goawaygo-staging resend
```

```bash
scripts/gcp-secrets.sh goawaygo-staging access
```

`jwt` generates the login-token key pair; `google` and `resend` ask for the value with hidden input (both optional
until those features are set up); `access` lets the backend's service account read them. Run `access` again after
adding a secret later.

| Secret Manager name             | Becomes                | Source                                        |
| ------------------------------- | ---------------------- | --------------------------------------------- |
| `goawaygo-db-owner-password`    | `DB_OWNER_PASSWORD`    | generated by `setup-hosted-db.sh`             |
| `goawaygo-db-app-password`      | `DB_APP_PASSWORD`      | generated by `setup-hosted-db.sh`             |
| `goawaygo-jwt-private-key`      | `JWT_PRIVATE_KEY`      | generated by `gcp-secrets.sh jwt`             |
| `goawaygo-jwt-public-key`       | `JWT_PUBLIC_KEY`       | generated by `gcp-secrets.sh jwt`             |
| `goawaygo-google-client-secret` | `GOOGLE_CLIENT_SECRET` | Google Auth Platform → Clients → GoAwayGo Web |
| `goawaygo-resend-api-key`       | `MAIL_PASSWORD`        | Resend → API Keys                             |

### 4. GitHub repository variables

Settings → Secrets and variables → Actions → **Variables** (identifiers and URLs, not secrets):

| Variable                                  | Example                                                                                     |
| ----------------------------------------- | ------------------------------------------------------------------------------------------- |
| `GCP_PROJECT_ID`                          | `goawaygo-staging`                                                                          |
| `GCP_REGION`                              | `asia-southeast1`                                                                           |
| `GCP_WIF_PROVIDER`                        | printed by `gcp-bootstrap.sh`                                                               |
| `GCP_DEPLOY_SA`                           | `goawaygo-deployer@goawaygo-staging.iam.gserviceaccount.com`                                |
| `GCP_RUNTIME_SA`                          | `goawaygo-backend@goawaygo-staging.iam.gserviceaccount.com`                                 |
| `DB_HOST`                                 | Neon host (no `-pooler`)                                                                    |
| `CORS_ALLOWED_ORIGINS`                    | `https://staging.goawaygo.com`                                                              |
| `WEB_BASE_URL`                            | `https://staging.goawaygo.com`                                                              |
| `API_BASE_URL`                            | `https://api.goawaygo.com`                                                                  |
| `DB_NAME`, `DB_OWNER_USER`, `DB_APP_USER` | optional; only to override the default names `goawaygo` / `goawaygo_owner` / `goawaygo_app` |
| `GOOGLE_CODE_FLOW_ENABLED`                | `true` once `goawaygo-google-client-secret` exists                                          |
| `MAIL_FROM`                               | `GoAwayGo <no-reply@goawaygo.com>` once Resend is set up (empty = no mail)                  |
| `FCM_PROJECT`                             | `goaway-483709` once the runtime account may send pushes (empty = no push)                  |

The deploy workflow stays inactive until `GCP_PROJECT_ID` is set.

### 5. Make the API public (once, after the first deploy)

The first deploy succeeds but every request gets Google's 401/403 until the service allows public calls. The deployer
account can't change that on purpose (least privilege), so a project owner runs once:

```bash
gcloud run services add-iam-policy-binding goawaygo-backend --region asia-southeast1 --member allUsers --role roles/run.invoker
```

Then re-run the workflow (Actions → Release → Run workflow) to get a green smoke test. The API's own
security (JWT, rate limits) protects every endpoint; this setting only lets requests reach it.

### 6. Custom domain `api.goawaygo.com`

1. Verify the domain with Google once: `gcloud domains verify goawaygo.com` opens Search Console; add the TXT
   record it shows in Cloudflare DNS.
2. After the first deploy:

   ```bash
   gcloud beta run domain-mappings create --service goawaygo-backend --domain api.goawaygo.com --region asia-southeast1
   ```

3. In Cloudflare DNS add the record it prints (CNAME `api` → `ghs.googlehosted.com`) as **DNS only** (grey cloud),
   so Google can issue the certificate. HTTPS is ready after up to ~15 minutes.

### 7. After the first deploy: check client IPs

Rate limits use the client address from `X-Forwarded-For`, trusted only from internal proxy addresses
(`FORWARD_HEADERS_STRATEGY=native`). Check once that the backend sees real client IPs, not the proxy's: with
`logging.level.org.apache.catalina.valves.RemoteIpValve=DEBUG` temporarily, or by checking that two people on
different networks don't share sign-in limits. If it sees a proxy address, set `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`
to match it.

## Database backups (5.7)

Every night at 02:00 India time, **Database backup** (`.github/workflows/backup.yml`) dumps the prod database
(`pg_dump`, custom format), encrypts it for the owner and uploads it to the Cloudflare R2 bucket `goawaygo-backups`
(`prod/goawaygo-prod-<date>.tar`). A bucket rule deletes copies after 30 days. Neon's own restore window is short;
these copies cover mistakes found later.

- **Encrypted for the owner only:** a fresh AES-256 key per copy, itself encrypted with the owner's public key
  (repository variable `BACKUP_PUBLIC_KEY`). The private key is on the owner's PC (`~/.goawaygo/backup/`), with an
  offline copy; GitHub and R2 can't read a copy.
- **No stored database password:** the job signs in to goawaygo-prod keyless (GitHub environment `backup`) and reads
  `goawaygo-db-owner-password` from Secret Manager.

Set up once (owner):

1. Key pair: `scripts/backup-keys.sh` (gh signed in as the owner). Copy `~/.goawaygo/backup/backup-private.pem`
   somewhere safe offline.
2. Cloudflare → R2 → **Create bucket** `goawaygo-backups` (location: Asia-Pacific) → **Settings → Object lifecycle
   rules → Add rule**: delete objects 30 days after upload. R2 → **Manage API tokens → Create API token**: Object
   Read & Write, only bucket `goawaygo-backups`; store its two values as the backend repository secrets
   `R2_BACKUP_ACCESS_KEY_ID` and `R2_BACKUP_SECRET_ACCESS_KEY` (`gh secret set ... -R goawaygoofficial/goawaygo_backend`,
   pasted into the hidden prompt).
3. Let the prod deployer read the database owner password (the job runs as it):

   ```bash
   gcloud secrets add-iam-policy-binding goawaygo-db-owner-password --project goawaygo-prod --member serviceAccount:goawaygo-deployer@goawaygo-prod.iam.gserviceaccount.com --role roles/secretmanager.secretAccessor
   ```

4. Actions → Database backup → **Run workflow**; the copy appears in R2.

**Restore** (practice it once, then whenever needed): download the copy from R2, then
`scripts/restore-backup.sh <file>.tar` (Docker running). It decrypts with the private key, checks the checksum and
loads the dump into the database you name: a scratch database (e.g. `goawaygo_restore_test` in the Neon project
`goawaygo-staging`) for practice; prod's own `goawaygo` only on purpose (it asks you to type "replace prod").

## Costs and limits (pre-launch)

- Cloud Run free tier (request-based billing): 180,000 vCPU-seconds, 360,000 GiB-seconds and 2 million requests a
  month, counted only while requests are handled. Uptime checks every 3 minutes use well under 1% of it.
- Neon free: ~1 GB storage, 100 compute hours a month; it sleeps after 5 idle minutes and wakes in under a second.
  To let it sleep, the connection pool keeps no idle connections (`minimum-idle=0`, closed after 2 minutes) and the
  health check never touches the database.
- Artifact Registry: 0.5 GB free; the cleanup policy keeps the newest 3 images (they share most layers).
- **The Google free trial ends 90 days after sign-up.** Upgrade the billing account to paid before then or the
  project is stopped. Usage inside the free tier stays $0; the budget alert warns before any charge.

## Health and uptime

`GET https://api.goawaygo.com/actuator/health` answers `{"status":"UP"}` without signing in. It only says the backend
is running: it doesn't check the database or email (so uptime checks don't wake Neon); those failures reach Sentry.
Nothing else from Spring Boot Actuator is exposed.

Better Stack (free plan, team Gmail) checks every 3 minutes and emails on downtime:

| Monitor                                              | Expect             | Every  |
| ---------------------------------------------------- | ------------------ | ------ |
| `https://api.goawaygo.com/actuator/health`           | status 200, `"UP"` | 3 min  |
| `https://staging.goawaygo.com`                       | status 200         | 3 min  |
| `https://downloads.goawaygo.com/android/latest.json` | status 200         | 30 min |

The API monitor allows a 30 s timeout, because the first check after an idle period waits for a cold start. Public
status page: `status.goawaygo.com` (CNAME in Cloudflare, DNS only).

## Outside APIs

Every call to an outside API (places, photos, flights, AI, ...) goes through `com.goawaygo.backend.external`:
timeouts, retries for temporary failures, a circuit breaker that pauses a failing provider for a minute, per-day and
per-month usage limits kept in the database (`external_api_usage`) that refuse calls before a free tier is exceeded,
and a database cache (`external_api_cache`) that also serves the last answer when the provider is down. When nothing
can be returned, the client gets a 503 with a friendly message.

To add a provider:

1. Sign up with the team Gmail; note the free tier's limits.
2. Add its settings to `application.properties`, limits **below** the free tier:

   ```properties
   goawaygo.external.providers.opentripmap.base-url=https://api.opentripmap.com/0.1
   goawaygo.external.providers.opentripmap.daily-limit=4500
   goawaygo.external.providers.opentripmap.cache-ttl=7d
   ```

   Names are lowercase letters and digits only. Optional: `connect-timeout` (3s), `read-timeout` (10s),
   `max-attempts` (3), `retry-wait` (500ms), `monthly-limit`.

3. If it needs a key, store it in Secret Manager (typed hidden; the backend can read it right away):

   ```bash
   scripts/gcp-secrets.sh goawaygo-staging provider opentripmap
   ```

4. Add the name to the repo variable `EXTERNAL_API_PROVIDERS` (space-separated, e.g. `opentripmap unsplash`). The deploy
   passes the key as `GOAWAYGO_EXTERNAL_PROVIDERS_<NAME>_APIKEY`. Locally, put that variable in `.env`.
5. Use it from a feature: `externalApis.client("opentripmap").cached(key, Type.class, (http, apiKey) -> ...)`.

Providers in use (4.1 Discover, `docs/destination-prompts.md`): `gemini`, `geminilite`, `geminilite35` (one per AI
model, all with the one Gemini key, secret `goawaygo-api-gemini`), `geoapify` (`goawaygo-api-geoapify`), `unsplash`
(`goawaygo-api-unsplash`), and `wikipedia`, `commons`, `wikidata` (no key). `EXTERNAL_API_PROVIDERS` is
`gemini geoapify unsplash`. Destination pages can take up to a minute on the free AI tier, so Cloud Run's request
timeout is 120 s (`deploy.yml`).

Calls so far are in the database: `SELECT * FROM external_api_usage ORDER BY period_start DESC;`. Reaching a limit
or a provider failing repeatedly is logged as an error, so it shows up in Sentry.

## Wanderbook photos, moderation and reports (4.2)

- **Photos** go to the Cloudflare R2 bucket `goawaygo-media` (public at `media.goawaygo.com`) through the backend,
  which keeps the original file and only removes its location data. Setup: create an R2 API token (Object Read &
  Write, bucket `goawaygo-media` only), store it with `scripts/gcp-secrets.sh goawaygo-staging r2`, then set the repo
  variable `WANDERBOOK_PHOTOS` to `true` and redeploy. Without it, uploads answer "Photos can't be saved right now".
  Uploads stop at 9.5 GB in total (`goawaygo.media.max-total-bytes`) to stay inside the free 10 GB.
- **Moderation**: OpenAI omni-moderation checks every photo and text (`openai` provider; key `goawaygo-api-openai`,
  add `openai` to `EXTERNAL_API_PROVIDERS`). If it can't answer, nothing is posted (users are asked to try again).
- **Reports** are emailed to `team@goawaygo.com` and reviewed by admins on the website (`/admin/reports`). Make
  someone an admin in the Neon SQL editor: `UPDATE users SET is_admin = true WHERE email = 'name@example.com';`

## Notifications (4.5)

- **The bell** works without any setup: notifications are saved in Postgres (`notifications`, kept 90 days) and
  listed by `/api/notifications`.
- **Push** (Android) goes through Firebase Cloud Messaging in the Firebase project `goaway-483709`, with the backend's
  own runtime service account: no key file. Once per environment, a project owner of `goaway-483709` grants it the
  sending role, then sets the repo variable `FCM_PROJECT` to `goaway-483709` and redeploys:

  ```bash
  gcloud projects add-iam-policy-binding goaway-483709     --member "serviceAccount:goawaygo-backend@goawaygo-staging.iam.gserviceaccount.com"     --role roles/firebasecloudmessaging.admin --condition None
  ```

  Without `FCM_PROJECT`, nothing is pushed (the bell still fills). A failed push is logged and dropped; tokens
  Firebase reports as gone are deleted.

- **Trip reminders** ("in 3 days", "tomorrow", in India time) are sent by `POST /api/notifications/reminders`, which
  the **Daily jobs** workflow calls at 09:00 IST (Cloud Run gives no CPU between requests, so no timer inside the
  backend). The call is public and only sends what is due, once; it also deletes notifications older than 90 days.
  Run it by hand: Actions → Daily jobs → Run workflow.
- **Announcements**: admins send them to everyone from the website, or `POST /api/admin/announcements`
  `{"title": "...", "text": "..."}`.

## Trip ideas (4.7)

- Home's **Ideas for your next trip** are made by the **Daily jobs** workflow's third step
  (`POST /api/discover/refresh-ideas`, public): once the list is 20 hours old it asks the AI for a new one, checks each
  place on Wikipedia, then translates it into every language, side by side. The list and its translations are kept in
  the `idea_pools` table, so a new backend instance serves them at once and nobody on Home waits for the AI. Called
  again with nothing due, it does nothing. Run it by hand: Actions → Daily jobs → Run workflow.

## Deleting accounts (4.6)

- **Delete account** (`POST /api/auth/delete-account`, after the password or Google) signs the account out
  everywhere, stops its pushes and hides it from others at once, and emails the date it will be deleted. Signing in
  again within 30 days keeps it.
- After 30 days, the **Daily jobs** workflow's second step (`POST /api/auth/purge-deleted-accounts`, public, only
  deletes what is due) deletes the account's photo files from R2, then the account itself, which takes everything it
  owns (every table cascades), and emails a last confirmation. If R2 can't be reached, that account waits for the
  next day, so no file is left behind.
- A request by email (the website's Delete my data page), once it is confirmed to come from the account's address:
  in Neon, set `deletion_requested_at = now()` on that user and delete their `refresh_tokens` rows; the daily job
  deletes the account 30 days later.

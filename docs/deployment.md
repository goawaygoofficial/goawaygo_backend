# Deployment

How the Goaway backend runs outside a developer machine. Pre-launch (alpha/beta) everything here is free; at
launch the database moves to Google Cloud SQL (Phase 5).

## Architecture

| Part             | Service                                                    | Address                  |
| ---------------- | ---------------------------------------------------------- | ------------------------ |
| Backend          | Google Cloud Run, `asia-southeast1` (Singapore)            | `api.goawaygo.com`       |
| Database         | Neon PostgreSQL 17, AWS Singapore (free plan)              | private (TLS only)       |
| Secrets          | Google Secret Manager                                      | read by the backend only |
| Container images | Google Artifact Registry `goaway` (only the newest 3 kept) | —                        |
| DNS              | Cloudflare (`goawaygo.com`)                                | —                        |
| Email            | Resend over SMTP                                           | `no-reply@goawaygo.com`  |

Why Singapore: Cloud Run can attach a custom domain directly there (not in Mumbai, which would need a paid load
balancer), and Neon has a Singapore region next to it. Users in India are ~50–70 ms away.

```
push to develop ─► CI (tests) ─► "Deploy staging" workflow ─► image in Artifact Registry ─► Cloud Run
                                                                            │
                                    Flyway migrates the database on start ◄─┘
```

## How a deploy works

`.github/workflows/deploy-staging.yml` runs after CI passes on `develop` (or by hand: Actions → Deploy staging →
Run workflow). It:

1. Gets short-lived Google credentials through Workload Identity Federation. **No Google key is stored in GitHub.**
2. Builds the [Dockerfile](../Dockerfile) and pushes `…/goaway/backend:<commit>`.
3. Deploys it to Cloud Run with plain settings as environment variables and secrets from Secret Manager.
4. Checks that `GET /api/users/me` answers 401 (the app is up and security is on).

On start the backend runs any new Flyway migrations as `goaway_owner`, then serves requests as `goaway_app`.

Cloud Run settings and why:

- `min-instances 0`: free while nobody uses it. The first request after ~15 idle minutes waits for a cold start
  (~7–10 s for Spring Boot); later requests are fast.
- `max-instances 1`: rate limits are kept in memory, so they only hold with a single instance.
- `cpu-throttling` (request-based billing): CPU is given, and billed, only while a request is being handled. An
  instance kept up between uptime checks costs nothing. So nothing may run after the response: emails are sent inside
  the request (`goaway.mail.async=false`), and forgot-password always takes at least 1.5 s so its timing doesn't
  reveal whether the account exists.
- `cpu-boost`: extra CPU during startup, shorter cold starts.

## Rollback

Every deploy is a Cloud Run revision. To go back to the previous one:

```bash
gcloud run revisions list --service goaway-backend --region asia-southeast1
```

```bash
gcloud run services update-traffic goaway-backend --region asia-southeast1 --to-revisions REVISION_NAME=100
```

Database migrations are not rolled back automatically. Migrations should be additive (new columns/tables) so the
previous revision keeps working.

## One-time setup (per environment)

Done once by a project owner. Commands run from the `backend` folder in Git Bash. Nothing below prints a secret.

### 1. Google Cloud project

1. Create the project (e.g. `goawaygo-staging`) with billing (free trial) and a budget alert
   (Billing → Budgets & alerts, ₹100, alerts at 50/90/100%).
2. Install the gcloud CLI and sign in: `gcloud auth login`.
3. Run:

   ```bash
   scripts/gcp-bootstrap.sh goawaygo-staging
   ```

   It enables the APIs, creates the image repository, the two service accounts and Workload Identity Federation,
   and prints the GitHub variables for step 4.

### 2. Database (Neon)

1. In Neon create project `goawaygo-staging`: PostgreSQL 17, region AWS Asia Pacific (Singapore).
2. Run (asks for the host and the admin password; Neon → Dashboard → Connect, use the host **without** `-pooler`):

   ```bash
   scripts/setup-hosted-db.sh goawaygo-staging
   ```

   It creates the `goaway` database with the `goaway_owner` / `goaway_app` roles (same script as local
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

| Secret Manager name           | Becomes                | Source                                      |
| ----------------------------- | ---------------------- | ------------------------------------------- |
| `goaway-db-owner-password`    | `DB_OWNER_PASSWORD`    | generated by `setup-hosted-db.sh`           |
| `goaway-db-app-password`      | `DB_APP_PASSWORD`      | generated by `setup-hosted-db.sh`           |
| `goaway-jwt-private-key`      | `JWT_PRIVATE_KEY`      | generated by `gcp-secrets.sh jwt`           |
| `goaway-jwt-public-key`       | `JWT_PUBLIC_KEY`       | generated by `gcp-secrets.sh jwt`           |
| `goaway-google-client-secret` | `GOOGLE_CLIENT_SECRET` | Google Auth Platform → Clients → Goaway Web |
| `goaway-resend-api-key`       | `MAIL_PASSWORD`        | Resend → API Keys                           |

### 4. GitHub repository variables

Settings → Secrets and variables → Actions → **Variables** (identifiers and URLs, not secrets):

| Variable                   | Example                                                                |
| -------------------------- | ---------------------------------------------------------------------- |
| `GCP_PROJECT_ID`           | `goawaygo-staging`                                                     |
| `GCP_REGION`               | `asia-southeast1`                                                      |
| `GCP_WIF_PROVIDER`         | printed by `gcp-bootstrap.sh`                                          |
| `GCP_DEPLOY_SA`            | `goaway-deployer@goawaygo-staging.iam.gserviceaccount.com`             |
| `GCP_RUNTIME_SA`           | `goaway-backend@goawaygo-staging.iam.gserviceaccount.com`              |
| `DB_HOST`                  | Neon host (no `-pooler`)                                               |
| `CORS_ALLOWED_ORIGINS`     | `https://staging.goawaygo.com`                                         |
| `WEB_BASE_URL`             | `https://staging.goawaygo.com`                                         |
| `API_BASE_URL`             | `https://api.goawaygo.com`                                             |
| `GOOGLE_CODE_FLOW_ENABLED` | `true` once `goaway-google-client-secret` exists                       |
| `MAIL_FROM`                | `Goaway <no-reply@goawaygo.com>` once Resend is set up (empty = no mail) |

The deploy workflow stays inactive until `GCP_PROJECT_ID` is set.

### 5. Make the API public (once, after the first deploy)

The first deploy succeeds but every request gets Google's 401/403 until the service allows public calls. The deployer
account can't change that on purpose (least privilege), so a project owner runs once:

```bash
gcloud run services add-iam-policy-binding goaway-backend --region asia-southeast1 --member allUsers --role roles/run.invoker
```

Then re-run the workflow (Actions → Deploy staging → Run workflow) to get a green smoke test. The API's own
security (JWT, rate limits) protects every endpoint; this setting only lets requests reach it.

### 6. Custom domain `api.goawaygo.com`

1. Verify the domain with Google once: `gcloud domains verify goawaygo.com` opens Search Console; add the TXT
   record it shows in Cloudflare DNS.
2. After the first deploy:

   ```bash
   gcloud beta run domain-mappings create --service goaway-backend --domain api.goawaygo.com --region asia-southeast1
   ```

3. In Cloudflare DNS add the record it prints (CNAME `api` → `ghs.googlehosted.com`) as **DNS only** (grey cloud),
   so Google can issue the certificate. HTTPS is ready after up to ~15 minutes.

### 7. After the first deploy: check client IPs

Rate limits use the client address from `X-Forwarded-For`, trusted only from internal proxy addresses
(`FORWARD_HEADERS_STRATEGY=native`). Check once that the backend sees real client IPs, not the proxy's: with
`logging.level.org.apache.catalina.valves.RemoteIpValve=DEBUG` temporarily, or by checking that two people on
different networks don't share sign-in limits. If it sees a proxy address, set `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`
to match it.

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

| Monitor                                           | Expect               | Every  |
| ------------------------------------------------- | -------------------- | ------ |
| `https://api.goawaygo.com/actuator/health`        | status 200, `"UP"`   | 3 min  |
| `https://staging.goawaygo.com`                    | status 200           | 3 min  |
| `https://downloads.goawaygo.com/android/latest.json` | status 200        | 30 min |

The API monitor allows a 30 s timeout, because the first check after an idle period waits for a cold start. Public
status page: `status.goawaygo.com` (CNAME in Cloudflare, DNS only).

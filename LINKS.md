# GoAwayGo — links

Every link for **dev**, **test** and **prod**, then the consoles they share. The same file is in all five repos.
Sign in to everything with the team account (kept out of these public repos). Everything else about each repo is
in its `README.md`; what each version brought is in `RELEASES.md`.

The first API request after ~15 idle minutes takes 7–10 s (cold start); that's normal.

## dev

Gets every version automatically after CI passes on `develop`. Test accounts and fake outside services. **Team only**:
the website asks for a one-time code sent to the team Gmail (Cloudflare Access), and only the team Gmail can sign in
(backend `TEAM_ONLY_EMAILS`).

| What                       | Link                                                                                                                                                                                                                                                                                                |
| -------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Website                    | https://dev.goawaygo.com                                                                                                                                                                                                                                                                            |
| API health                 | https://api-dev.goawaygo.com/actuator/health                                                                                                                                                                                                                                                        |
| Website hosting (Pages)    | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/pages/view/goawaygo-web-dev                                                                                                                                                                                                            |
| API service (Cloud Run)    | https://console.cloud.google.com/run/detail/asia-southeast1/goawaygo-backend-dev/metrics?project=goawaygo-staging                                                                                                                                                                                   |
| API logs                   | https://console.cloud.google.com/logs/query;query=resource.labels.service_name%3D%22goawaygo-backend-dev%22?project=goawaygo-staging                                                                                                                                                                |
| Errors (Sentry, dev)       | https://goawaygo.sentry.io/issues/?environment=dev                                                                                                                                                                                                                                                  |
| Database `goawaygo_dev`    | https://console.neon.tech/app/projects/wispy-meadow-14336089 (Neon project goawaygo-staging)                                                                                                                                                                                                        |
| Secrets (`goawaygo-dev-*`) | https://console.cloud.google.com/security/secret-manager?project=goawaygo-staging                                                                                                                                                                                                                   |
| GitHub environment `dev`   | https://github.com/goawaygoofficial/goawaygo_backend/settings/environments · https://github.com/goawaygoofficial/goawaygo_web_frontend/settings/environments                                                                                                                                        |
| Deploys to dev (Release)   | [backend](https://github.com/goawaygoofficial/goawaygo_backend/actions/workflows/release.yml) · [website](https://github.com/goawaygoofficial/goawaygo_web_frontend/actions/workflows/release.yml) · [app](https://github.com/goawaygoofficial/goawaygo_app_frontend/actions/workflows/release.yml) |
| Tests run on (branch)      | `develop` of the automation and performance test repos                                                                                                                                                                                                                                              |

## test

Gets a version from dev through the Controller, with the owner's approval. Test accounts and fake outside services.
**Team only**, like dev.

| What                         | Link                                                                                                                                                                                                                                                                                                                            |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Website                      | https://test.goawaygo.com                                                                                                                                                                                                                                                                                                       |
| API health                   | https://api-test.goawaygo.com/actuator/health                                                                                                                                                                                                                                                                                   |
| Website hosting (Pages)      | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/pages/view/goawaygo-web-test                                                                                                                                                                                                                                       |
| API service (Cloud Run)      | https://console.cloud.google.com/run/detail/asia-southeast1/goawaygo-backend-test/metrics?project=goawaygo-staging                                                                                                                                                                                                              |
| API logs                     | https://console.cloud.google.com/logs/query;query=resource.labels.service_name%3D%22goawaygo-backend-test%22?project=goawaygo-staging                                                                                                                                                                                           |
| Errors (Sentry, test)        | https://goawaygo.sentry.io/issues/?environment=test                                                                                                                                                                                                                                                                             |
| Database `goawaygo_test`     | https://console.neon.tech/app/projects/wispy-meadow-14336089 (Neon project goawaygo-staging)                                                                                                                                                                                                                                    |
| Secrets (`goawaygo-test-*`)  | https://console.cloud.google.com/security/secret-manager?project=goawaygo-staging                                                                                                                                                                                                                                               |
| Deploys to test (Controller) | [backend](https://github.com/goawaygoofficial/goawaygo_backend/actions/workflows/controller.yaml) · [website](https://github.com/goawaygoofficial/goawaygo_web_frontend/actions/workflows/controller.yaml) · [app](https://github.com/goawaygoofficial/goawaygo_app_frontend/actions/workflows/controller.yaml) (branch `test`) |
| Tests run on (branch)        | `test` of the automation and performance test repos                                                                                                                                                                                                                                                                             |

## prod

Gets a version from test through the Controller, with the owner's approval. Real users: smoke tests only, read-only.

| What                               | Link                                                                                                       |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| Website                            | https://goawaygo.com (`https://www.goawaygo.com` redirects here)                                           |
| API health                         | https://api.goawaygo.com/actuator/health                                                                   |
| Status page                        | https://status.goawaygo.com                                                                                |
| Uptime monitors + alerts           | https://incidents.betterstack.com/team/t617423/monitors                                                    |
| Website hosting (Pages)            | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/pages/view/goawaygo-web-prod                  |
| API service (Cloud Run)            | https://console.cloud.google.com/run/detail/asia-southeast1/goawaygo-backend/metrics?project=goawaygo-prod |
| API logs                           | https://console.cloud.google.com/logs/query?project=goawaygo-prod                                          |
| API errors (Error Reporting)       | https://console.cloud.google.com/errors?project=goawaygo-prod                                              |
| API 5xx alert (Monitoring)         | https://console.cloud.google.com/monitoring/alerting?project=goawaygo-prod                                 |
| Errors (Sentry, production)        | https://goawaygo.sentry.io/issues/?environment=production                                                  |
| Sentry alerts                      | https://goawaygo.sentry.io/monitors/alerts/                                                                |
| Database `goawaygo`                | https://console.neon.tech/app/projects/summer-king-77273449 (Neon project goawaygo-prod)                   |
| Nightly database backups (R2)      | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/r2/default/buckets/goawaygo-backups           |
| Backup runs                        | https://github.com/goawaygoofficial/goawaygo_backend/actions/workflows/backup.yml                          |
| Secrets                            | https://console.cloud.google.com/security/secret-manager?project=goawaygo-prod                             |
| Custom domain `api`                | https://console.cloud.google.com/run/domains?project=goawaygo-prod                                         |
| Keyless deploy (Workload Identity) | https://console.cloud.google.com/iam-admin/workload-identity-pools?project=goawaygo-prod                   |
| IAM (who can do what)              | https://console.cloud.google.com/iam-admin/iam?project=goawaygo-prod                                       |
| Deploys to prod (Controller)       | same Controller links as test, on branch `main`                                                            |
| Search Console                     | https://search.google.com/search-console?resource_id=sc-domain:goawaygo.com                                |
| Website traffic (no cookies)       | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/web-analytics                                 |
| Latest Android build               | https://downloads.goawaygo.com/android/latest.json · https://goawaygo.com/download                         |

Public pages: [Privacy Policy](https://goawaygo.com/privacy) · [Terms of Use](https://goawaygo.com/terms) ·
[Delete my data](https://goawaygo.com/delete-my-data) · [Community Guidelines](https://goawaygo.com/community-guidelines)
· [Wanderbook reports (admins)](https://goawaygo.com/admin/reports). Contact: support@goawaygo.com ·
privacy@goawaygo.com (both forward to the team Gmail).

Tip: every API response has an `X-Request-Id` header. Paste it into Logs Explorer (`jsonPayload.requestId="…"`) or
Sentry search (`request_id:…`) to see everything that happened in that request.

## Shared by dev, test and prod

### Code, builds and deploys (GitHub)

| What                                   | Link                                                                                                                                                                                                                                 |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Organisation                           | https://github.com/goawaygoofficial                                                                                                                                                                                                  |
| Backend repo / PRs / Actions           | https://github.com/goawaygoofficial/goawaygo_backend · [/pulls](https://github.com/goawaygoofficial/goawaygo_backend/pulls) · [/actions](https://github.com/goawaygoofficial/goawaygo_backend/actions)                               |
| Website repo / PRs / Actions           | https://github.com/goawaygoofficial/goawaygo_web_frontend · [/pulls](https://github.com/goawaygoofficial/goawaygo_web_frontend/pulls) · [/actions](https://github.com/goawaygoofficial/goawaygo_web_frontend/actions)                |
| App repo / PRs / Actions               | https://github.com/goawaygoofficial/goawaygo_app_frontend · [/pulls](https://github.com/goawaygoofficial/goawaygo_app_frontend/pulls) · [/actions](https://github.com/goawaygoofficial/goawaygo_app_frontend/actions)                |
| Automation tests (Playwright, Maestro) | https://github.com/goawaygoofficial/goawaygo_automation_tests · [Actions](https://github.com/goawaygoofficial/goawaygo_automation_tests/actions)                                                                                     |
| Performance tests (k6)                 | https://github.com/goawaygoofficial/goawaygo_performance_tests · [Actions](https://github.com/goawaygoofficial/goawaygo_performance_tests/actions)                                                                                   |
| Releases (where each version runs)     | [backend](https://github.com/goawaygoofficial/goawaygo_backend/releases) · [website](https://github.com/goawaygoofficial/goawaygo_web_frontend/releases) · [app](https://github.com/goawaygoofficial/goawaygo_app_frontend/releases) |
| Backend repository variables           | https://github.com/goawaygoofficial/goawaygo_backend/settings/variables/actions                                                                                                                                                      |

Each repo's Actions run: CI (build/tests), Secret scan (Gitleaks), Dependency scan (OSV-Scanner, also every Monday).
A red ✗ on `develop` or a failed Monday scan needs a look.

### Google Cloud

**`goawaygo-prod`** runs prod; **`goawaygo-staging`** runs dev and test and holds the one image registry (images are
built once, for every environment).

| What                                       | Link                                                                     |
| ------------------------------------------ | ------------------------------------------------------------------------ |
| goawaygo-staging dashboard (dev, test)     | https://console.cloud.google.com/home/dashboard?project=goawaygo-staging |
| goawaygo-prod dashboard                    | https://console.cloud.google.com/home/dashboard?project=goawaygo-prod    |
| Artifact Registry (backend images, shared) | https://console.cloud.google.com/artifacts?project=goawaygo-staging      |
| **Billing, credits, budget alerts**        | https://console.cloud.google.com/billing                                 |

**Free trial ends 2027-01-14**: upgrade the billing account before then (reminder: ~2027-01-04), or the projects stop.

### Cloudflare (domain, websites, files, email forwarding)

| What                                          | Link                                                                                                                |
| --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| Account home                                  | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082                                                        |
| Pages projects (dev, test, prod)              | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/workers-and-pages                                      |
| DNS records for goawaygo.com                  | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/goawaygo.com/dns/records                               |
| Redirect Rules (www → goawaygo.com)           | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/goawaygo.com/rules/redirect-rules                      |
| App downloads (R2 `goawaygo-downloads`)       | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/r2/default/buckets/goawaygo-downloads                  |
| Wanderbook photos (R2 `goawaygo-media`)       | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/r2/default/buckets/goawaygo-media                      |
| R2 API tokens (photos, backups)               | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/r2/api-tokens                                          |
| Email forwarding (privacy@, support@)         | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/goawaygo.com/email/routing/routes                      |
| DMARC reports                                 | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/goawaygo.com/email/dmarc-management                    |
| Zero Trust: Access for the dev and test sites | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/one/access-controls/apps                               |
| Access service token for the tests (1 year)   | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/one/access-controls/service-credentials/service-tokens |
| Domain registration and renewal               | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/domains                                                |

### Errors, statistics and email

| What                                               | Link                                                                                                                                                          |
| -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Sentry: all errors (EU, org goawaygo)              | https://goawaygo.sentry.io/issues/                                                                                                                            |
| Sentry projects: backend · website · app           | https://goawaygo.sentry.io/projects/goawaygo-backend/ · https://goawaygo.sentry.io/projects/goawaygo-web/ · https://goawaygo.sentry.io/projects/goawaygo-app/ |
| Sentry data scrubbing (organisation)               | https://goawaygo.sentry.io/settings/security-and-privacy/                                                                                                     |
| Product analytics (PostHog EU, project "GoAwayGo") | https://eu.posthog.com/project/315148                                                                                                                         |
| PostHog live events · privacy settings             | https://eu.posthog.com/project/315148/activity/explore · https://eu.posthog.com/project/315148/settings/project                                               |
| Sent emails (Resend: verify, password reset)       | https://resend.com/emails                                                                                                                                     |
| Sending domain `goawaygo.com` (Resend)             | https://resend.com/domains                                                                                                                                    |

Emails go out as `GoAwayGo <no-reply@goawaygo.com>`.

### Google Sign-In, Firebase and Android (Google Cloud project `goaway-483709`)

| What                                                | Link                                                                      |
| --------------------------------------------------- | ------------------------------------------------------------------------- |
| OAuth clients (web, Android, iOS)                   | https://console.cloud.google.com/auth/clients?project=goaway-483709       |
| OAuth consent screen / branding                     | https://console.cloud.google.com/auth/branding?project=goaway-483709      |
| Firebase project                                    | https://console.firebase.google.com/project/goaway-483709/overview        |
| Firebase App Distribution (send builds, testers)    | https://console.firebase.google.com/project/goaway-483709/appdistribution |
| Beta sign-up link (public)                          | https://appdistribution.firebase.dev/i/2d125c5f843e31fa                   |
| Billing for this project (Blaze, ₹100 budget alert) | https://console.cloud.google.com/billing?project=goaway-483709            |

### Outside services the features use

| What                                                                    | Link                                                           |
| ----------------------------------------------------------------------- | -------------------------------------------------------------- |
| Gemini API keys and usage (Google AI Studio; project "GoAwayGo Gemini") | https://aistudio.google.com/apikey                             |
| Geoapify project and usage                                              | https://myprojects.geoapify.com                                |
| Unsplash app (demo limits, apply for production)                        | https://unsplash.com/oauth/applications                        |
| OpenAI API keys and usage                                               | https://platform.openai.com/api-keys                           |
| Travelpayouts (programs, statistics, payouts)                           | https://app.travelpayouts.com                                  |
| KAYAK Affiliate Network (sandbox; portal after approval)                | https://affiliates.kayak.com                                   |
| Vulnerability database (scan findings)                                  | https://osv.dev                                                |
| Outside API calls so far (Neon SQL editor)                              | `SELECT * FROM external_api_usage ORDER BY period_start DESC;` |

## Suggested routine

- **When an alert email arrives** (Better Stack, Sentry or Google Cloud): status page → Sentry issue → Logs Explorer
  with the request ID.
- **Weekly (5 min):** Sentry new issues · GitHub Actions (any red, Monday dependency scan) · Neon compute hours ·
  Google Cloud billing/credits · R2 `goawaygo-media` size · last night's backup · open Wanderbook reports · PostHog
  and Cloudflare stats if curious.
- **Before each Android release:** latest.json and the download page show the new build; Firebase shows it sent.

## Giving a new developer access

Invite people with **their own** accounts; never share the team Gmail or its password. Give the least access that
does the job, and remove it when they leave.

| Service                                                         | Where to invite                                     | Typical access for a developer                                                                        |
| --------------------------------------------------------------- | --------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| GitHub                                                          | each repo → Settings → Collaborators → Add people   | **Write** (they open PRs; merging stays with the owner)                                               |
| Google Cloud `goawaygo-prod` (`goawaygo-staging` for dev, test) | IAM & Admin → IAM → Grant access                    | **Logs Viewer** + **Cloud Run Viewer** (read-only); deploys stay with GitHub Actions                  |
| Firebase / Google project `goaway-483709`                       | Firebase → Project settings → Users and permissions | **Viewer**; add their debug SHA-1 to the Android OAuth client so Google Sign-In works in their builds |
| Sentry                                                          | Settings → Members → Invite                         | **Member**                                                                                            |
| PostHog                                                         | Settings → Members → Invite                         | **Member**                                                                                            |
| Better Stack                                                    | Team → Invite                                       | **Responder** (gets alerts)                                                                           |
| Cloudflare                                                      | Manage Account → Members → Invite                   | **Read-only** unless they work on DNS/hosting                                                         |
| Neon                                                            | Settings → People → Invite                          | Only if they need the hosted database (most work runs on the local Docker database)                   |

Never shared: the Android upload key (`~/.goawaygo/` on the release PC), the backup private key, Secret Manager
values, `.env` files, the Neon admin password.

**Tools to install** (details in each repo's README → Getting started):

| Repo         | Tools                                                                                                           |
| ------------ | --------------------------------------------------------------------------------------------------------------- |
| backend      | JDK 17+, Docker Desktop (local database and tests)                                                              |
| web_frontend | Node 24 (see `.nvmrc`), npm                                                                                     |
| app_frontend | Flutter 3.47.7 (same as CI), Android Studio / Android SDK                                                       |
| test repos   | Node (see `.nvmrc`); k6 for performance tests; Maestro for app tests                                            |
| all          | Git, GitHub CLI (`gh`), Gitleaks (pre-commit secret scan); `gcloud` and `wrangler` only for deploy/release work |

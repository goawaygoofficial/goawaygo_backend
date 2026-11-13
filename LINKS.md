<!-- Copy of Goaway/LINKS.md (workspace root, shared by all repos). Keep the copies in sync. -->

# GoAwayGo — links

Every place to check that GoAwayGo works, and every account it runs on. Production unless marked; dev and test
are the same with `dev.` / `test.` (website) and `api-dev.` / `api-test.` (API).
Sign in to everything with the team account (kept out of these public repos). What each service is _for_:
`FOUNDATIONS.md`. How to ship: `RELEASING.md`. What's next: `ROADMAP.md`.

## 1. Is it up? (open these first)

| What                      | Link                                                    | Healthy looks like                            |
| ------------------------- | ------------------------------------------------------- | --------------------------------------------- |
| Public status page        | https://status.goawaygo.com                             | "All services are online"                     |
| Uptime monitors + alerts  | https://incidents.betterstack.com/team/t617423/monitors | 3 monitors green: API, Website, App downloads |
| Website                   | https://goawaygo.com                                    | Home loads                                    |
| API health                | https://api.goawaygo.com/actuator/health                | `{"status":"UP"}`                             |
| Latest Android build info | https://downloads.goawaygo.com/android/latest.json      | Shows the current version and build           |
| App download page         | https://goawaygo.com/download                           | Version, size, "Download for Android"         |
| dev / test websites       | https://dev.goawaygo.com · https://test.goawaygo.com    | Home loads                                    |

The first API request after ~15 idle minutes takes 7–10 s (cold start); that's normal.

## 2. Is anything broken? (errors and logs)

| What                                       | Link                                                                                                                                    |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------- |
| Sentry — all errors (EU, org goawaygo)     | https://goawaygo.sentry.io/issues/                                                                                                      |
| Sentry — backend                           | https://goawaygo.sentry.io/projects/goawaygo-backend/                                                                                   |
| Sentry — website                           | https://goawaygo.sentry.io/projects/goawaygo-web/                                                                                       |
| Sentry — app                               | https://goawaygo.sentry.io/projects/goawaygo-app/                                                                                       |
| Backend logs, prod (Logs Explorer)         | https://console.cloud.google.com/logs/query?project=goawaygo-prod                                                                       |
| Backend errors, prod (Error Reporting)     | https://console.cloud.google.com/errors?project=goawaygo-prod                                                                           |
| Backend service, prod (revisions, metrics) | https://console.cloud.google.com/run/detail/asia-southeast1/goawaygo-backend/metrics?project=goawaygo-prod                              |
| Backend logs and errors, dev and test      | https://console.cloud.google.com/logs/query?project=goawaygo-staging · https://console.cloud.google.com/errors?project=goawaygo-staging |

Tip: every API response has an `X-Request-Id` header. Paste it into Logs Explorer (`jsonPayload.requestId="…"`) or
Sentry search (`request_id:…`) to see everything that happened in that request.

## 3. Who uses it? (statistics)

| What                                                   | Link                                                                       |
| ------------------------------------------------------ | -------------------------------------------------------------------------- |
| Website traffic (Cloudflare Web Analytics, no cookies) | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/web-analytics |
| Product analytics (PostHog EU, only users who allowed) | https://eu.posthog.com/project/315148                                      |
| PostHog — live events                                  | https://eu.posthog.com/project/315148/activity/explore                     |
| Beta testers and installs (Firebase App Distribution)  | https://console.firebase.google.com/project/goaway-483709/appdistribution  |

## 4. Code, builds and deploys (GitHub)

| What                                   | Link                                                                                                                                                                                                                                                                                                                       |
| -------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Organisation                           | https://github.com/goawaygoofficial                                                                                                                                                                                                                                                                                        |
| Backend repo / PRs / Actions           | https://github.com/goawaygoofficial/goawaygo_backend · [/pulls](https://github.com/goawaygoofficial/goawaygo_backend/pulls) · [/actions](https://github.com/goawaygoofficial/goawaygo_backend/actions)                                                                                                                     |
| Website repo / PRs / Actions           | https://github.com/goawaygoofficial/goawaygo_web_frontend · [/pulls](https://github.com/goawaygoofficial/goawaygo_web_frontend/pulls) · [/actions](https://github.com/goawaygoofficial/goawaygo_web_frontend/actions)                                                                                                      |
| App repo / PRs / Actions               | https://github.com/goawaygoofficial/goawaygo_app_frontend · [/pulls](https://github.com/goawaygoofficial/goawaygo_app_frontend/pulls) · [/actions](https://github.com/goawaygoofficial/goawaygo_app_frontend/actions)                                                                                                      |
| Backend releases (→ dev)               | https://github.com/goawaygoofficial/goawaygo_backend/actions/workflows/release.yml                                                                                                                                                                                                                                         |
| Automation tests (Playwright, Maestro) | https://github.com/goawaygoofficial/goawaygo_automation_tests · [Actions](https://github.com/goawaygoofficial/goawaygo_automation_tests/actions)                                                                                                                                                                           |
| Performance tests (k6)                 | https://github.com/goawaygoofficial/goawaygo_performance_tests · [Actions](https://github.com/goawaygoofficial/goawaygo_performance_tests/actions)                                                                                                                                                                         |
| Test runs and their reports            | [backend](https://github.com/goawaygoofficial/goawaygo_backend/actions/workflows/release.yml) · [website](https://github.com/goawaygoofficial/goawaygo_web_frontend/actions/workflows/release.yml) · [app](https://github.com/goawaygoofficial/goawaygo_app_frontend/actions/workflows/release.yml) (each run → Artifacts) |
| Controller (→ test, prod)              | [backend](https://github.com/goawaygoofficial/goawaygo_backend/actions/workflows/controller.yaml) · [website](https://github.com/goawaygoofficial/goawaygo_web_frontend/actions/workflows/controller.yaml) · [app](https://github.com/goawaygoofficial/goawaygo_app_frontend/actions/workflows/controller.yaml)            |
| Releases (where each version runs)     | [backend](https://github.com/goawaygoofficial/goawaygo_backend/releases) · [website](https://github.com/goawaygoofficial/goawaygo_web_frontend/releases) · [app](https://github.com/goawaygoofficial/goawaygo_app_frontend/releases)                                                                                       |
| Repo variables (backend)               | https://github.com/goawaygoofficial/goawaygo_backend/settings/variables/actions                                                                                                                                                                                                                                            |

Each repo's Actions run: CI (build/tests), Secret scan (Gitleaks), Dependency scan (OSV-Scanner, also every Monday).
A red ✗ on `develop` or a failed Monday scan needs a look.

## 5. Backend platform (Google Cloud)

Two projects: **`goawaygo-prod`** runs production (`api.goawaygo.com`); **`goawaygo-staging`** runs dev and test
(`api-dev`, `api-test`) and holds the image registry (images are built once, for every environment). The links open
the prod project; change `project=` to `goawaygo-staging` for dev and test.

| What                                       | Link                                                                                     |
| ------------------------------------------ | ---------------------------------------------------------------------------------------- |
| Project dashboard                          | https://console.cloud.google.com/home/dashboard?project=goawaygo-prod                    |
| Cloud Run services                         | https://console.cloud.google.com/run?project=goawaygo-prod                               |
| Custom domains (`api.goawaygo.com`)        | https://console.cloud.google.com/run/domains?project=goawaygo-prod                       |
| Secret Manager (keys and passwords)        | https://console.cloud.google.com/security/secret-manager?project=goawaygo-prod           |
| Artifact Registry (backend images, shared) | https://console.cloud.google.com/artifacts?project=goawaygo-staging                      |
| Keyless deploy (Workload Identity)         | https://console.cloud.google.com/iam-admin/workload-identity-pools?project=goawaygo-prod |
| IAM (who can do what)                      | https://console.cloud.google.com/iam-admin/iam?project=goawaygo-prod                     |
| **Billing, credits, budget alerts**        | https://console.cloud.google.com/billing                                                 |

**Free trial ends 2027-01-14** — upgrade the billing account before then (reminder: ~2027-01-04), or the project stops.

## 6. Database (Neon)

| What                                                                                                                                 | Link                                   |
| ------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------- |
| Neon console: project goawaygo-prod (prod, database `goawaygo`); project goawaygo-staging (dev `goawaygo_dev`, test `goawaygo_test`) | https://console.neon.tech/app/projects |

Postgres 17, AWS Singapore. Check **Monitoring** (compute hours: free tier 100/month) and **Tables** (e.g.
`external_api_usage` to see outside-API calls per day). Prod is backed up every night, encrypted, to R2
`goawaygo-backups` (`backend/docs/deployment.md`, "Database backups").

## 7. Website, domain, downloads, email forwarding (Cloudflare)

| What                                            | Link                                                                                               |
| ----------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| Cloudflare account home                         | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082                                       |
| Website hosting (Pages: prod, dev, test)        | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/workers-and-pages                     |
| DNS records for goawaygo.com                    | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/goawaygo.com/dns/records              |
| Redirect Rules (www, staging → goawaygo.com)    | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/goawaygo.com/rules/redirect-rules     |
| App downloads storage (R2 `goawaygo-downloads`) | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/r2/default/buckets/goawaygo-downloads |
| Wanderbook photos (R2 `goawaygo-media`)         | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/r2/default/buckets/goawaygo-media     |
| Database backups (R2 `goawaygo-backups`)        | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/r2/default/buckets/goawaygo-backups   |
| R2 API tokens (photos, backups)                 | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/r2/api-tokens                         |
| Email forwarding (privacy@, support@)           | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/goawaygo.com/email/routing/routes     |
| Domain registration and renewal                 | https://dash.cloudflare.com/d76546af7ff5304c8319ec3df10c6082/domains                               |

Pages projects: `goawaygo-web-prod` (`goawaygo.com`, `www.goawaygo.com`), `goawaygo-web-dev` (`dev.goawaygo.com`),
`goawaygo-web-test` (`test.goawaygo.com`); GitHub Actions uploads to them (`web_frontend/docs/deployment.md`).
`www.goawaygo.com` redirects 301 to `goawaygo.com` with the path kept. There are no other website addresses (the
pre-launch `staging.goawaygo.com` was deleted on 14 Nov 2026).

## 8. Email sending (Resend)

| What                                 | Link                       |
| ------------------------------------ | -------------------------- |
| Sent emails (verify, password reset) | https://resend.com/emails  |
| Sending domain `goawaygo.com`        | https://resend.com/domains |

Sends as `GoAwayGo <no-reply@goawaygo.com>`. Replies and contact go to **privacy@** / **support@goawaygo.com**, which
forward to the team Gmail.

## 9. Google Sign-In, Firebase and Android (Google Cloud project `goaway-483709`)

| What                                                | Link                                                                      |
| --------------------------------------------------- | ------------------------------------------------------------------------- |
| OAuth clients (web, Android, iOS)                   | https://console.cloud.google.com/auth/clients?project=goaway-483709       |
| OAuth consent screen / branding                     | https://console.cloud.google.com/auth/branding?project=goaway-483709      |
| Firebase project                                    | https://console.firebase.google.com/project/goaway-483709/overview        |
| Firebase App Distribution (send builds)             | https://console.firebase.google.com/project/goaway-483709/appdistribution |
| Beta sign-up link (public)                          | https://appdistribution.firebase.dev/i/2d125c5f843e31fa                   |
| Billing for this project (Blaze, ₹100 budget alert) | https://console.cloud.google.com/billing?project=goaway-483709            |

## 10. Public pages users see

| Page                        | Link                                                                        |
| --------------------------- | --------------------------------------------------------------------------- |
| Website                     | https://goawaygo.com                                                        |
| Get the app                 | https://goawaygo.com/download                                               |
| Privacy Policy              | https://goawaygo.com/privacy                                                |
| Terms of Use                | https://goawaygo.com/terms                                                  |
| Delete my data              | https://goawaygo.com/delete-my-data                                         |
| Community Guidelines        | https://goawaygo.com/community-guidelines                                   |
| Wanderbook reports (admins) | https://goawaygo.com/admin/reports                                          |
| Status page                 | https://status.goawaygo.com                                                 |
| Search Console (Google)     | https://search.google.com/search-console?resource_id=sc-domain:goawaygo.com |
| Contact                     | support@goawaygo.com · privacy@goawaygo.com                                 |

## 11. Feature services (Discover, Wanderbook, Book)

| What                                                                    | Link                                                           |
| ----------------------------------------------------------------------- | -------------------------------------------------------------- |
| Gemini API keys and usage (Google AI Studio; project "GoAwayGo Gemini") | https://aistudio.google.com/apikey                             |
| Geoapify projects and usage                                             | https://myprojects.geoapify.com                                |
| Unsplash app (demo limits, apply for production)                        | https://unsplash.com/oauth/applications                        |
| OpenAI API keys and usage                                               | https://platform.openai.com/api-keys                           |
| Travelpayouts (programs, statistics, payouts)                           | https://app.travelpayouts.com                                  |
| KAYAK Affiliate Network (API keys, approval)                            | https://affiliates.kayak.com                                   |
| Outside API calls so far (Neon SQL editor)                              | `SELECT * FROM external_api_usage ORDER BY period_start DESC;` |

## 12. Reference

| What                                   | Link                                                      |
| -------------------------------------- | --------------------------------------------------------- |
| Vulnerability database (scan findings) | https://osv.dev                                           |
| PostHog privacy settings (project)     | https://eu.posthog.com/project/315148/settings/project    |
| Sentry data scrubbing (organisation)   | https://goawaygo.sentry.io/settings/security-and-privacy/ |

## Suggested routine

- **When an alert email arrives** (Better Stack or Sentry): status page → Sentry issue → Logs Explorer with the
  request ID.
- **Weekly (5 min):** Sentry new issues · GitHub Actions (any red, Monday dependency scan) · Neon compute hours ·
  Google Cloud billing/credits · R2 `goawaygo-media` size · open Wanderbook reports · PostHog and Cloudflare stats if
  curious.
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

Never shared: the Android upload key (`~/.goawaygo/` on the release PC), Secret Manager values, `.env` files, the
Neon admin password.

**Tools to install** (details in each repo's README → Getting started):

| Repo         | Tools                                                                                                           |
| ------------ | --------------------------------------------------------------------------------------------------------------- |
| backend      | JDK 17+, Docker Desktop (local database and tests)                                                              |
| web_frontend | Node 24 (see `.nvmrc`), npm                                                                                     |
| app_frontend | Flutter 3.47.7 (same as CI), Android Studio / Android SDK                                                       |
| all          | Git, GitHub CLI (`gh`), Gitleaks (pre-commit secret scan); `gcloud` and `wrangler` only for deploy/release work |

<!-- Copy of Goaway/CLEANUP.md (workspace root, shared by all repos). Keep the copies in sync. -->

# GoAwayGo — clean-up after launch (record)

Done 13–14 Nov 2026, after the website launched on `goawaygo.com`. Rule: everything **dev**, **test** and **prod**
use stays; everything else from before (the pre-launch "staging" setup) is deleted. The only website addresses are
`goawaygo.com` (+ `www` redirecting to it), `dev.goawaygo.com` and `test.goawaygo.com`.

## What stays (who uses what)

| Service            | dev                                                           | test                                                 | prod                                                  |
| ------------------ | ------------------------------------------------------------- | ---------------------------------------------------- | ----------------------------------------------------- |
| Website            | Pages `goawaygo-web-dev`, dev.goawaygo.com                    | Pages `goawaygo-web-test`, test.goawaygo.com         | Pages `goawaygo-web-prod`, goawaygo.com (+ www → 301) |
| API (Cloud Run)    | `goawaygo-backend-dev` (goawaygo-staging), api-dev            | `goawaygo-backend-test` (goawaygo-staging), api-test | `goawaygo-backend` (goawaygo-prod), api               |
| Database (Neon)    | `goawaygo_dev`, project goawaygo-staging                      | `goawaygo_test`, project goawaygo-staging            | `goawaygo`, project goawaygo-prod                     |
| Secrets            | `goawaygo-dev-*` + shared `goawaygo-api-*` (goawaygo-staging) | `goawaygo-test-*` + shared (goawaygo-staging)        | goawaygo-prod                                         |
| GitHub environment | `dev`                                                         | `test`                                               | `prod`, `backup`                                      |
| Sentry environment | `dev`                                                         | `test`                                               | `production`                                          |

"goawaygo-staging" (Google Cloud and Neon project names) is the home of dev **and** test, decided at the start of
MVP 2; project IDs can't be renamed. Shared on purpose: the image registry (goawaygo-staging), the outside-API keys
(incl. KAYAK, kept for later), Google sign-in clients (web, Android release + debug, iOS), Firebase (Android app),
Resend, R2 (`goawaygo-downloads` → downloads.goawaygo.com, `goawaygo-media` → media.goawaygo.com,
`goawaygo-backups`), email forwarding (support@, privacy@), Better Stack (status.goawaygo.com), PostHog, Search
Console, Travelpayouts, Gemini (project "GoAwayGo Gemini"), Geoapify, Unsplash, OpenAI. Developer tools stay:
localhost origins for sign-in, the AI GitHub apps.

## What was deleted or changed

- **Google Cloud:** old v1.6.0 service `goawaygo-backend` in goawaygo-staging (it was still connected to the prod
  database) and its 6 secrets (copies of prod's database passwords, JWT keys, R2 keys); projects
  `goawaygo-prod-514417` (the duplicate) and "My First Project" shut down (gone for good after 30 days); the unused
  Firebase "Browser key" (no web app in Firebase); the Gemini project renamed "GoAwayGo Gemini".
- **Cloudflare:** Pages project `goawaygo-web-frontend` (111 old deployments first); `staging.goawaygo.com` (DNS
  record and redirect rule); the old "goawaygo.com to website" rule (the www rule is now named "www.goawaygo.com to
  goawaygo.com"); the unused "Cloudflare Agent Token" (26 permissions); app builds 5–9 in downloads (10–12 kept);
  downloads CORS now allows only the 3 sites + localhost. Added: monitor-only DMARC record.
- **Google sign-in, backend CORS, Sentry:** staging removed from the web client's origins and from prod's
  `CORS_ALLOWED_ORIGINS` (prod redeployed: the API refuses staging); Sentry environments `staging` and
  `local-verification` hidden; the website's Sentry project accepts only the 3 sites.
- **Neon:** the empty `goawaygo-staging` database and role in the prod project; the unused default `neondb` and role in
  the dev/test project.
- **Better Stack:** the staging website monitor; the website monitor checks `goawaygo.com/home`; the status page logo
  links to goawaygo.com.
- **GitHub:** the empty `staging` environment and its 60 old deployment records; every environment now holds all of
  its own variables and only shared ones are repository variables (backend #90; the image build has its own
  `IMAGE_*`); the old repository-level variables.
- **Code and docs:** the staging site out of the website (#80) and app (#77: `env/prod.env`, Sentry `production`);
  `dev:dev-api` instead of the staging API; package name `goawaygo-web`; LINKS, FOUNDATIONS, RELEASING, ROADMAP,
  CLAUDE.md, deployment docs updated.
- **This PC:** stray `dbg.png`, `backend/Artifact`, `backend/Enabling`.

## Left for the owner (needs your sign-in)

- GitHub → Settings → Applications → **Cloudflare Workers and Pages** → Uninstall (GitHub asks for your passkey).
- PostHog, Resend, KAYAK: sign in once so their settings can be checked for old addresses.
- The repository variable `WEB_BASE_URL` (backend) goes after the next promotion to `main` (the old prod smoke test on
  `main` still reads it).
- The testers' installed app (build 12) still links to staging for Privacy/Terms/shared plans; the next app build
  (13) links to goawaygo.com.

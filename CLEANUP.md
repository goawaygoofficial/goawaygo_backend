<!-- Copy of Goaway/CLEANUP.md (workspace root, shared by all repos). Keep the copies in sync. -->

# GoAwayGo — clean-up after launch (plan)

Goal: every service holds only what **dev**, **test** and **prod** use. Everything left from the pre-launch "staging"
setup (one Cloud Run service, `staging.goawaygo.com`, shared prod database) goes, or is written down as kept on purpose.

Inventory taken 13 Nov 2026 from the live services. Approved 14 Nov 2026: Claude does every item; the KAYAK key stays.

## Progress (14 Nov 2026)

- Done: 1.1–1.7, 2.1, 2.2, 2.3 (kept), 2.4 (the registry already keeps the newest 10 images), 3.1, 3.2 (111 old
  deployments deleted first, then the project), 4.1, 4.2, 5.1, 5.2 (shut down, gone for good after 30 days), 5.3
  (renamed "GoAwayGo Gemini"), 6.1; Search Console sitemap (LAUNCH.md part C).
- In PRs: 7.1–7.2 backend #90, 7.3 app #77, 7.4 website #80, 7.5 automation tests #7, 7.6 shared docs.
- After #90 merges and a dev deploy used it: delete the old repository-level variables (`GCP_PROJECT_ID`,
  `GCP_DEPLOY_SA`, `GCP_RUNTIME_SA`, `GCP_WIF_PROVIDER`, `DB_HOST`, `CORS_ALLOWED_ORIGINS`, `WEB_BASE_URL`,
  `API_BASE_URL`, `WANDERBOOK_PHOTOS`, `BACKUP_PUBLIC_KEY`), then one prod deploy (your approval) so prod runs with its
  own complete environment.
- After the next app build: 6.2 (hide `staging` in Sentry goawaygo-app).
- Also found: the Cloudflare GitHub app is still installed on the GitHub organisation for the deleted Pages project;
  uninstall it.

## Target: who uses what

| Service            | dev                                                           | test                                                  | prod                                            |
| ------------------ | ------------------------------------------------------------- | ----------------------------------------------------- | ----------------------------------------------- |
| Website            | Pages `goawaygo-web-dev`, dev.goawaygo.com                    | Pages `goawaygo-web-test`, test.goawaygo.com          | Pages `goawaygo-web-prod`, goawaygo.com (+ www) |
| API (Cloud Run)    | `goawaygo-backend-dev` in goawaygo-staging, api-dev           | `goawaygo-backend-test` in goawaygo-staging, api-test | `goawaygo-backend` in goawaygo-prod, api        |
| Database (Neon)    | `goawaygo_dev` in project goawaygo-staging                    | `goawaygo_test` in project goawaygo-staging           | `goawaygo` in project goawaygo-prod             |
| Secrets            | `goawaygo-dev-*` + shared `goawaygo-api-*` (goawaygo-staging) | `goawaygo-test-*` + shared (goawaygo-staging)         | goawaygo-prod                                   |
| Images             | one registry for all (goawaygo-staging)                       | same                                                  | same                                            |
| Sentry environment | `dev`                                                         | `test`                                                | `production`                                    |
| GitHub environment | `dev`                                                         | `test`                                                | `prod` (+ `backup`)                             |

"goawaygo-staging" (the Google Cloud and Neon projects) stays: it is the home of dev **and** test, as decided at the
start of MVP 2. Shared by dev and test on purpose: the outside-API keys, the Google OAuth web client, Resend, R2
`goawaygo-media` (prod only in practice), PostHog, Firebase.

## 1. Safe now (nothing uses it)

| #   | Where                                                    | What                                                                                                                            | Who |
| --- | -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- | --- |
| 1.1 | Google Auth Platform (goaway-483709) → Goaway Web client | Remove origin `https://staging.goawaygo.com` (it only redirects now; keep localhost, dev, test, goawaygo.com, www)              | Me  |
| 1.2 | GitHub backend repo variable `CORS_ALLOWED_ORIGINS`      | Drop `https://staging.goawaygo.com`; prod redeploy (your approval)                                                              | Me  |
| 1.3 | Better Stack                                             | Delete the paused monitor "GoAwayGo website" (staging)                                                                          | Me  |
| 1.4 | Cloudflare → Redirect Rules                              | Delete the disabled rule "goawaygo.com to website"                                                                              | Me  |
| 1.5 | Cloudflare → My Profile → API Tokens                     | Revoke "Cloudflare Agent Token - 2026-10-14" (26 permissions, unused for 30 days). The deploy token "GitHub Pages deploy" stays | Me  |
| 1.6 | GitHub backend repo                                      | Delete the empty environment `staging`                                                                                          | Me  |
| 1.7 | This PC                                                  | Delete stray files `Goaway/dbg.png`, `backend/Artifact`, `backend/Enabling` (empty, from a mistyped command)                    | Me  |

## 2. The old staging backend (goawaygo-staging)

The old service `goawaygo-backend` still runs **v1.6.0** and is connected to the **prod database**, with copies of
prod's database passwords. It has no domain and no traffic since prod moved to goawaygo-prod (11 Nov). The plan kept
it until ~19 Nov for a rollback; backups, the prod project and the Controller make that unnecessary.

| #   | What                                                                                                                                                                                                                                                | Who                  |
| --- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------- |
| 2.1 | Delete Cloud Run service `goawaygo-backend` in goawaygo-staging (dev and test services stay)                                                                                                                                                        | Me                   |
| 2.2 | Delete its secrets in goawaygo-staging: `goawaygo-db-app-password`, `goawaygo-db-owner-password`, `goawaygo-jwt-private-key`, `goawaygo-jwt-public-key`, `goawaygo-r2-access-key-id`, `goawaygo-r2-secret-access-key` (only that service used them) | You (Secret Manager) |
| 2.3 | `goawaygo-api-kayak` in both projects: no service uses it (KAYAK is sandbox only). Delete, or keep for when KAYAK approves?                                                                                                                         | You decide           |
| 2.4 | Prune old images in Artifact Registry `goawaygo` (149 MB): add a clean-up policy keeping the last 10 versions                                                                                                                                       | Me                   |

## 3. Old website project (Cloudflare Pages `goawaygo-web-frontend`)

It is still connected to the GitHub repo and rebuilds every push to `develop` (last build 1 hour ago), and holds
`staging.goawaygo.com`. The staging **redirect** stays (the current beta app and old shared links use it), so:

| #   | What                                                                                                                                                                     | Who |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --- |
| 3.1 | DNS: change `staging` from CNAME `goawaygo-web-frontend.pages.dev` to a proxied placeholder A record `192.0.2.1` (the redirect rule keeps working, no project behind it) | Me  |
| 3.2 | Delete Pages project `goawaygo-web-frontend` (its builds are all in GitHub Releases anyway)                                                                              | Me  |

## 4. Neon

| #   | What                                                                                                                                                                | Who                       |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------- |
| 4.1 | Project goawaygo-prod: database `goawaygo-staging` and role `goawaygo-staging_owner`, left from before the split. Check it's empty/unused (SQL Editor), then delete | Me (check) → You (delete) |
| 4.2 | Project goawaygo-staging: default database `neondb` and role `neondb_owner`, unused                                                                                 | Me                        |

## 5. Extra Google Cloud projects

| #   | Project                                                             | What                                                                                                  | Who            |
| --- | ------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- | -------------- |
| 5.1 | `goawaygo-prod-514417` ("goawaygo-prod", no billing, nothing in it) | The duplicate made during C1. Shut down (recoverable for 30 days)                                     | You            |
| 5.2 | `project-3623319d-…` ("My First Project", no billing, empty)        | Google's default project. Shut down                                                                   | You            |
| 5.3 | `gen-lang-client-0559019379` ("Default Gemini Project")             | **Keep**: it holds the Gemini API key ("GoAwayGo", free tier). Optionally rename to "GoAwayGo Gemini" | You (optional) |

## 6. Sentry

| #   | What                                                                                                                                      | Who     |
| --- | ----------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| 6.1 | Hide environments `staging` and `local-verification` in goawaygo-backend and goawaygo-web (history stays)                                 | Me      |
| 6.2 | goawaygo-app: hide `staging` after the next app build (6.3)                                                                               | Me      |
| 6.3 | The app still reports as `staging` (`SENTRY_ENVIRONMENT=staging`); it talks to prod, so the next build reports `production` (part of 7.3) | Me (PR) |

## 7. Code, config and docs (PRs, your "check CI and merge")

| #   | Repo            | What                                                                                                                                                                                                                                                                                                                                                                                                                          |
| --- | --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 7.1 | backend         | GitHub variables: today prod reads several **repository-level** values left from staging days (`DB_HOST`, `CORS_ALLOWED_ORIGINS`, `WEB_BASE_URL`, `API_BASE_URL`, and `GCP_*` pointing at goawaygo-staging). Move prod's into the `prod` environment and keep only shared ones at repo level, so each environment is complete and nothing is inherited by accident. Workflows checked first; a prod and a dev deploy prove it |
| 7.2 | backend         | Remove staging leftovers in code: `application.properties`, `TestFakes`, `RequestIdFilterTests`, `daily-jobs.yml`, `deploy.yml`, `tests.yml`, one-off `scripts/gcp-copy-secrets.sh` (the move to goawaygo-prod is done), `gcp-bootstrap.sh`, `restore-backup.sh`                                                                                                                                                              |
| 7.3 | app             | `env/staging.env` → `env/prod.env` (the beta uses the prod API), `SENTRY_ENVIRONMENT=production`, update `release-android.sh`, `main.dart`, monitoring/analytics, `pubspec.yaml`, tests and docs                                                                                                                                                                                                                              |
| 7.4 | website         | Remove the `staging.goawaygo.com` entries: `src/lib/env.ts`, `public/_headers`, `vite.config.ts`, `analytics.ts`, `monitoring.ts`, `package.json`                                                                                                                                                                                                                                                                             |
| 7.5 | test repos      | `setup-test-secrets.sh`, `security.spec.ts`, READMEs                                                                                                                                                                                                                                                                                                                                                                          |
| 7.6 | all + workspace | Rewrite **LINKS.md** for dev/test/prod (it still says "Staging (pre-launch)" and links staging.goawaygo.com and the old Pages project); update FOUNDATIONS.md, RELEASING.md, ROADMAP.md, CLAUDE.md, READMEs, `docs/deployment.md`, `docs/release-android.md`; tick LAUNCH.md part C                                                                                                                                           |

## 8. Small, optional, free

| #   | What                                                                                                                               |
| --- | ---------------------------------------------------------------------------------------------------------------------------------- |
| 8.1 | R2 `goawaygo-downloads` (17 files, 687 MB): keep the last 3 app versions, delete older APKs (free tier is 10 GB, so only tidiness) |
| 8.2 | R2 `goawaygo-media` has 1 object (1.2 MB) from the staging days: check whether it's a real Wanderbook photo before anything        |
| 8.3 | Cloudflare recommends a DMARC record for goawaygo.com (stops fake emails from @goawaygo.com)                                       |

## Order

1 → 2 → 3 → 4 → 5 → 6.1 → 7 (7.1 first, then 7.2–7.6) → 6.2 after the next app build → 8 if wanted.
Each step that deletes something is listed above; your OK on this plan is the OK for the "Me" items, except where it
says "You decide".

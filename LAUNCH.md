<!-- Copy of Goaway/LAUNCH.md (workspace root, shared by all repos). Keep the copies in sync. -->

# Launching goawaygo.com (MVP 2, 5.7)

The website goes live for customers on `goawaygo.com`, served from `main` (the `prod` environment). Decided
2026-11-13: launch now; the lawyer review of the legal pages and the native-speaker check of the 6 other languages
come right after launch. Work through the parts in order and tick each box. Commands run in Git Bash; console steps
name the exact menus.

## A. Before the switch

**Website build** (done in code, 5.7)

- [x] Public pages pre-rendered with their own titles and descriptions; `sitemap.xml` and `robots.txt`; dev, test,
      staging and `pages.dev` copies send `X-Robots-Tag: noindex` (website PR #70).
- [x] 50 destination pages rendered from prod's data during each prod website deploy (#71).
- [x] A website version with both is in prod (v1.11.2, 13 Nov 2026: 50 of 50 destination pages, 59 sitemap entries).

**Database backups** (backend `docs/deployment.md`, "Database backups")

- [x] Key pair made (`scripts/backup-keys.sh`) and an offline copy of the private key kept.
- [x] R2 bucket `goawaygo-backups` with a 30-day lifecycle rule; its token stored as `R2_BACKUP_ACCESS_KEY_ID` /
      `R2_BACKUP_SECRET_ACCESS_KEY`; the deployer may read `goawaygo-db-owner-password`.
- [x] First backup run green (Actions → Database backup → Run workflow); the copy is in R2.
- [x] Practice restore into a scratch database (`scripts/restore-backup.sh`) worked.

**Monitoring and alerts**

- [x] Better Stack → Monitors: **GoAwayGo website (goawaygo.com)** checks `https://goawaygo.com/home` for the keyword
      "GoAwayGo" every 3 minutes (follows redirects, so it works before and after the switch) → email the primary
      responder. It shows as "Goaway website" on `status.goawaygo.com`, with the API and App Downloads monitors; the
      old staging website monitor is off the status page (pause it after the switch, part C).
- [x] Sentry → Monitors → Alerts (all email the suggested assignees, else recently active members):
      "Prod new error (backend + web)" and "App new error" (a new issue, an escalating issue or a regression;
      backend and web in environment `production`, the app in all environments), and "Prod error burst (backend +
      web)" and "App error burst" (an issue seen more than 50 times in an hour, at most one email an hour).
- [x] Google Cloud (project goawaygo-prod) → Monitoring: policy "GoAwayGo prod API errors (5xx)" (Cloud Run
      `goawaygo-backend` 5xx responses over 10 in 5 minutes) → email channel "GoAwayGo owner". Free within the
      Cloud Monitoring allowance.
- [x] Budget alert on the billing account still set ("goaway-auth-budget", ₹100 a month, whole billing account,
      alerts at 50, 90, 100 and 150%).

**Google sign-in in production mode** (Google Cloud project `goaway-483709`)

- [x] Google Auth Platform → **Audience** → Publishing status: **Publish app** (from "Testing" to "In production").
      With only the basic scopes (email, profile, openid) no Google review is needed; the consent screen shows the
      app name and `goawaygo.com`.
- [x] Google Auth Platform → **Branding**: home page `https://goawaygo.com`, privacy policy
      `https://goawaygo.com/privacy`, terms `https://goawaygo.com/terms`, authorised domain `goawaygo.com`.
- [x] Clients → **GoAwayGo Web** → Authorised JavaScript origins: add `https://goawaygo.com` and
      `https://www.goawaygo.com` (keep staging, dev and test for now).

## B. The switch (about 30 minutes; pick a quiet time)

1. [x] **Backend allows the new address.** Repository variables of goawaygo_backend (Settings → Secrets and variables
       → Actions → Variables): `CORS_ALLOWED_ORIGINS` = `https://goawaygo.com,https://www.goawaygo.com,https://staging.goawaygo.com`
       and `WEB_BASE_URL` = `https://goawaygo.com` (links in emails). Then redeploy prod with the same version:
       Actions → Controller → Run workflow → branch `main` → (version empty) → approve **prod**.
2. [x] **Cloudflare → Rules**: turn off the Redirect Rule "goawaygo.com to website".
3. [x] **Cloudflare → DNS → Records**: delete the two placeholder A records `@` and `www` (`192.0.2.1`). (Done by
       step 4: adding the custom domains replaced them with CNAMEs to `goawaygo-web-prod.pages.dev`.)
4. [x] **Cloudflare → Workers & Pages → goawaygo-web-prod → Custom domains**: add `goawaygo.com`, then
       `www.goawaygo.com`. Cloudflare creates the DNS records and the certificate (a few minutes).
5. [x] **www to the main address**: Rules → Redirect Rules → Create: when hostname equals `www.goawaygo.com`, redirect
       301 to `https://goawaygo.com${path}` with the query string kept.
6. [x] **GitHub variables**: website environment `prod`: `WEB_URL` = `https://goawaygo.com`; website repository
       variable `PROD_WEB_URL` = `https://goawaygo.com` (the prod smoke tests).
7. [x] **Check**: `https://goawaygo.com/home` loads, sign-in with email and with Google works, a destination page
       loads, `https://goawaygo.com/sitemap.xml` and `/robots.txt` answer, `https://www.goawaygo.com` redirects.
8. [x] **staging.goawaygo.com**: Redirect Rule "staging.goawaygo.com to goawaygo.com": hostname
       `staging.goawaygo.com` → 301 to `https://goawaygo.com${path}` with the query string kept. The custom domain
       stays on `goawaygo-web-frontend` for now (removing it can remove its DNS record, and the redirect needs one);
       it goes when that project is retired (part C). The beta app's legal links (`WEBSITE_URL`) keep working
       through the redirect; the next app build points at goawaygo.com directly.

Switched on 13 Nov 2026 (after a Cloudflare Workers API outage delayed steps 2–8). Checked: `/`, `/home`,
`/privacy`, `/terms` and a destination page answer 200 with their own titles, no `X-Robots-Tag`; `sitemap.xml`
(59 entries) and `robots.txt` answer; http, `www` and `staging` redirect 301 with the path and query kept; the API
accepts `https://goawaygo.com`; the Google button loads. Still to try by hand: signing in with email and with Google.

## C. After the switch

- [ ] Google Search Console → Add property `goawaygo.com` (domain; verified already for Cloud Run) → Sitemaps →
      submit `https://goawaygo.com/sitemap.xml`.
- [ ] Apply to Agoda again (the partner application), now that the site is readable without JavaScript.
- [ ] Lawyer review of the Privacy Policy, Terms and Community Guidelines (decided: right after launch).
- [ ] Native-speaker check of the 6 other languages (decided: right after launch).
- [ ] Remove `staging.goawaygo.com` from the backend's `CORS_ALLOWED_ORIGINS` and the Google origins once nothing uses
      it (after the next app build), and retire the old Pages project `goawaygo-web-frontend`.
- [ ] Remove the disabled Redirect Rule "goawaygo.com to website" (kept for now, off).
- [ ] Better Stack: pause (or delete) the old "GoAwayGo website" monitor of `staging.goawaygo.com`; status page
      → Settings → logo link to `https://goawaygo.com`.
- [ ] Next app build: `WEBSITE_URL=https://goawaygo.com` in `env/staging.env`.
- [ ] ROADMAP.md: tick 5.7.

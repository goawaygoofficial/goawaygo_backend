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
- [ ] A website version with both is in prod (Controller: develop → test → main, your approvals).

**Database backups** (backend `docs/deployment.md`, "Database backups")

- [ ] Key pair made (`scripts/backup-keys.sh`) and an offline copy of the private key kept.
- [ ] R2 bucket `goawaygo-backups` with a 30-day lifecycle rule; its token stored as `R2_BACKUP_ACCESS_KEY_ID` /
      `R2_BACKUP_SECRET_ACCESS_KEY`; the deployer may read `goawaygo-db-owner-password`.
- [ ] First backup run green (Actions → Database backup → Run workflow); the copy is in R2.
- [ ] Practice restore into a scratch database (`scripts/restore-backup.sh`) worked.

**Monitoring and alerts**

- [ ] Better Stack → Monitors → **Create monitor** for `https://goawaygo.com` (alert when the URL is unavailable,
      every 3 minutes, email). Keep the API monitor (`https://api.goawaygo.com/actuator/health`); point the
      "Website" monitor at goawaygo.com after the switch. Add both to the status page.
- [ ] Sentry → Alerts → **Create alert** → Issues, for each project (goawaygo-backend, goawaygo-web, goawaygo-app):
      "A new issue is created" with environment `production` → email. And one "Number of errors > 50 in 1 hour".
- [ ] Google Cloud (project goawaygo-prod) → Monitoring → Alerting → **Create policy**: Cloud Run revision, metric
      "Request count" filtered to response code class 5xx, over 10 in 5 minutes → email notification channel (made
      in the same screen). Free within the Cloud Monitoring allowance.
- [ ] Budget alert on the billing account still set (Billing → Budgets & alerts).

**Google sign-in in production mode** (Google Cloud project `goaway-483709`)

- [ ] Google Auth Platform → **Audience** → Publishing status: **Publish app** (from "Testing" to "In production").
      With only the basic scopes (email, profile, openid) no Google review is needed; the consent screen shows the
      app name and `goawaygo.com`.
- [ ] Google Auth Platform → **Branding**: home page `https://goawaygo.com`, privacy policy
      `https://goawaygo.com/privacy`, terms `https://goawaygo.com/terms`, authorised domain `goawaygo.com`.
- [ ] Clients → **GoAwayGo Web** → Authorised JavaScript origins: add `https://goawaygo.com` and
      `https://www.goawaygo.com` (keep staging, dev and test for now).

## B. The switch (about 30 minutes; pick a quiet time)

1. [ ] **Backend allows the new address.** Repository variables of goawaygo_backend (Settings → Secrets and variables
       → Actions → Variables): `CORS_ALLOWED_ORIGINS` = `https://goawaygo.com,https://www.goawaygo.com,https://staging.goawaygo.com`
       and `WEB_BASE_URL` = `https://goawaygo.com` (links in emails). Then redeploy prod with the same version:
       Actions → Controller → Run workflow → branch `main` → (version empty) → approve **prod**.
2. [ ] **Cloudflare → Rules**: turn off the Redirect Rule "goawaygo.com to website".
3. [ ] **Cloudflare → DNS → Records**: delete the two placeholder A records `@` and `www` (`192.0.2.1`).
4. [ ] **Cloudflare → Workers & Pages → goawaygo-web-prod → Custom domains**: add `goawaygo.com`, then
       `www.goawaygo.com`. Cloudflare creates the DNS records and the certificate (a few minutes).
5. [ ] **www to the main address**: Rules → Redirect Rules → Create: when hostname equals `www.goawaygo.com`, redirect
       301 to `https://goawaygo.com${path}` with the query string kept.
6. [ ] **GitHub variables**: website environment `prod`: `WEB_URL` = `https://goawaygo.com`; website repository
       variable `PROD_WEB_URL` = `https://goawaygo.com` (the prod smoke tests).
7. [ ] **Check**: `https://goawaygo.com/home` loads, sign-in with email and with Google works, a destination page
       loads, `https://goawaygo.com/sitemap.xml` and `/robots.txt` answer, `https://www.goawaygo.com` redirects.
8. [ ] **staging.goawaygo.com**: Workers & Pages → goawaygo-web-frontend → Custom domains → remove
       `staging.goawaygo.com`; then a Redirect Rule: hostname `staging.goawaygo.com` → 301 to
       `https://goawaygo.com${path}`. The beta app's legal links (`WEBSITE_URL`) keep working through it; the next
       app build points at goawaygo.com directly.

## C. After the switch

- [ ] Google Search Console → Add property `goawaygo.com` (domain; verified already for Cloud Run) → Sitemaps →
      submit `https://goawaygo.com/sitemap.xml`.
- [ ] Apply to Agoda again (the partner application), now that the site is readable without JavaScript.
- [ ] Lawyer review of the Privacy Policy, Terms and Community Guidelines (decided: right after launch).
- [ ] Native-speaker check of the 6 other languages (decided: right after launch).
- [ ] Remove `staging.goawaygo.com` from the backend's `CORS_ALLOWED_ORIGINS` and the Google origins once nothing uses
      it (after the next app build), and retire the old Pages project `goawaygo-web-frontend`.
- [ ] Next app build: `WEBSITE_URL=https://goawaygo.com` in `env/staging.env`.
- [ ] ROADMAP.md: tick 5.7.

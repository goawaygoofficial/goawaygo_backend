<!-- Copy of Goaway/ROADMAP.md (workspace root, shared by all repos). Keep the copies in sync. -->

# GoAwayGo roadmap

Where the project stands and what comes next. Tick items as they ship (merged to `develop` and live on staging).
Shared by all three repos; how to ship is in `RELEASING.md`.

## Done

- [x] **Phase 1 — Foundation:** repos, branching (`develop`/`main`), CI, secret scanning, Dependabot.
- [x] **Phase 2 — Backend and login:** PostgreSQL, JWT + rotating refresh tokens, Google Sign-In, rate limits,
      email verification and password reset, website and app connected, UI matched across web and app.
- [x] **Phase 3 — Staging:** `api.goawaygo.com` (Cloud Run + Neon), `staging.goawaygo.com` (Cloudflare Pages),
      Resend email, Android builds via `staging.goawaygo.com/download` and Firebase beta (1.0.0 build 4).

## Phase 4 — Features

Decisions (recommended; to confirm after 4.0): partner links now (Travelpayouts), own booking after the company is
registered · AI planner on Claude Haiku 5.5 · Wanderbook beta = posts + photos + follow + likes (comments later) ·
phone number optional, no SMS · India-first (INR default).

| Sub-phase                        | What users get                                                                                 | Status  |
| -------------------------------- | ---------------------------------------------------------------------------------------------- | ------- |
| **4.0 Foundations**              | Error tracking, uptime alerts, analytics with consent, legal pages, API toolkit                | Next    |
| **4.1 Destinations & discovery** | Real destination info (facts, visa, weather, currency, attractions, photos, map), smart search | Planned |
| **4.2 Flights, Stays, Rentals**  | Real prices; "Book" opens the partner (affiliate commission)                                   | Planned |
| **4.3 Personal**                 | Saved, Recently Viewed, My Trips, edit profile, profile photo, delete account                  | Planned |
| **4.4 Wanderbook**               | Posts with photos, follow, likes, feed, search, report and block                               | Planned |
| **4.5 AI trip planner**          | Day-by-day itinerary, saved to My Trips                                                        | Planned |
| **4.6 Notifications**            | Push notifications and the bell on Home                                                        | Planned |
| **4.7 Launch readiness**         | Beta fixes, speed, accessibility, security review                                              | Planned |

### 4.0 Foundations

Accounts (team Gmail, free): Sentry, PostHog, Better Stack. Existing: Cloudflare (Web Analytics, Email Routing),
Google Cloud.

- [x] **4.0a Error tracking** — Sentry in backend (Spring Boot), website (React) and app (Flutter): one
      organisation, a project per part, `staging` environment, release = version/commit, no personal data sent
      (`sendDefaultPii` off, emails/tokens scrubbed). Backend: JSON logs that Google Cloud Logging understands,
      request IDs in every log line and error.
- [x] **4.0b Health and uptime** — `GET /actuator/health` (status only, nothing else exposed); Better Stack checks
      the API and the website every 3 min and emails on downtime; public status page `status.goawaygo.com`.
- [x] **4.0c Legal and privacy** — Privacy Policy, Terms of Use, and Delete-my-data page on the website (DPDP Act
      2023 / Rules 2025 based: notice, consent, purposes, rights, retention, grievance contact); links in the website
      footer, the sign-up screens and the app's Profile. Contact addresses `privacy@` and `support@goawaygo.com` via
      Cloudflare Email Routing (forward to the team Gmail). Written for international law (GDPR, US state laws, DPDP,
      LGPD). **Still to do before launch: lawyer review.**
- [x] **4.0d Analytics with consent** — Cloudflare Web Analytics on the website (cookieless, aggregate, no consent
      needed); PostHog (product analytics + feature flags) in website and app, **only after the user agrees**, with a
      short event list and no personal data in events; consent choice changeable in Profile.
- [ ] **4.0e API toolkit (backend)** — one way to call outside APIs: timeouts, retries and circuit breaker
      (Resilience4j), a database cache table with expiry, per-provider usage counters that stop before a free limit
      is exceeded, keys from Secret Manager. Plus a vulnerability scan of dependencies in CI (OSV-Scanner) for all
      repos.

### Later sub-phases

Detailed checklists are added when each sub-phase starts. Accounts expected: OpenTripMap, Unsplash (4.1);
Travelpayouts and partner programs (4.2); R2 bucket `goaway-user-media` (4.3); Anthropic, ~$5 credit (4.5); Firebase
config for push (4.6).

## Phase 5 — Launch

Company registration · Play Console + Android developer verification (before the 2027 rule reaches India) · production
environment (`goawaygo.com`, Cloud SQL) · Google billing upgraded before the trial ends (2027-01-14) · DPDP compliance
review (substantive obligations from 13 May 2027).

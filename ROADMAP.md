<!-- Copy of Goaway/ROADMAP.md (workspace root, shared by all repos). Keep the copies in sync. -->

# GoAwayGo roadmap

Where the project stands and what comes next. Tick items as they ship (merged to `develop` and live on dev).
Shared by all three repos; how to ship is in `RELEASING.md`.

## Done

- [x] **Phase 1 — Foundation:** repos, branching (`develop`/`main`), CI, secret scanning, Dependabot.
- [x] **Phase 2 — Backend and login:** PostgreSQL, JWT + rotating refresh tokens, Google Sign-In, rate limits,
      email verification and password reset, website and app connected, UI matched across web and app.
- [x] **Phase 3 — Staging:** `api.goawaygo.com` (Cloud Run + Neon), `staging.goawaygo.com` (Cloudflare Pages),
      Resend email, Android builds via `staging.goawaygo.com/download` and Firebase beta (1.0.0 build 4).

## Phase 4 — Features

Direction (confirmed 2026-10-25): **free resources only** until launch · **global market** (English first, every
currency) · bookings through **affiliate partners** (commission; users book on the partner's site) · Wanderbook like
Instagram · phone sign-in with SMS (MVP 2). Each sub-phase's own choices (providers, limits) are decided when it
starts and recorded in its section below.

| Sub-phase                 | What users get                                                                                                                                           | Status |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| **4.0 Foundations**       | Error tracking, uptime alerts, analytics with consent, legal pages, API toolkit                                                                          | Done   |
| **4.1 Discover**          | Real destination pages (info, attractions, photos, weather, currency, visa, map), AI search, "Recently searched", rotating suggestions, Save to My Trips | Done   |
| **4.2 Wanderbook**        | Instagram-style posts with photos, comments, likes, follow, profiles (photo, bio, cover); report, block and moderation                                   | Done   |
| **4.3 Book (affiliates)** | Flights, Stays, Rentals search with prices; "Book" opens the partner; commission disclosure                                                              | Done   |
| **4.4 AI trip planner**   | Day-by-day itinerary, saved to My Trips                                                                                                                  | Done   |
| **4.5 Notifications**     | Push notifications and the bell on Home                                                                                                                  | Done   |
| **4.6 Me**                | My Trips, Recently viewed, edit profile and photo, delete account (phone sign-in with SMS moved to MVP 2)                                                | Done   |
| **4.7 Global readiness**  | More languages, currencies and regions; accessibility, speed, security review before launch                                                              | Done   |

**How we build (decided 2026-10-31):** in MVP rounds. **MVP 1** builds every sub-phase below (4.1 to 4.7) without
stopping for fix rounds; fixes, changes and new ideas are collected for **MVP 2, 3, 4, ...** after that.

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
- [x] **4.0e API toolkit (backend)** — one way to call outside APIs: timeouts, retries and circuit breaker
      (Resilience4j), a database cache table with expiry, per-provider usage counters that stop before a free limit
      is exceeded, keys from Secret Manager. Plus a vulnerability scan of dependencies in CI (OSV-Scanner) for all
      repos.

### 4.1 Discover

Decided 2026-10-26. Design and reasons: `backend/docs/destination-prompts.md`.

- **Search:** type a place (country, region, city, landmark), pick or search, open its destination page. Place
  search: **Geoapify** (free, worldwide OpenStreetMap data). Unknown places: "We couldn't find that place".
- **Destination page:** shown all at once when ready (a travel animation until then). A sliding banner of up to 8
  tourist spots: the AI's top attractions with their **Wikipedia/Wikimedia Commons** photos (same name and nearby,
  so a wrong place can't appear), plus spots **Wikidata** lists nearby with **Unsplash** photos (only if the photo's
  description names the spot). Credit on every photo; fetched live, never stored.
- **9 info cards** (Quick Facts, Visa & Entry Rules, Best Time to Visit, Top Attractions, Best Areas to Stay, Food &
  Dining, Getting Around, Travel Insurance, Money & Connectivity): **one AI request per page open** returning the 9
  cards as separate JSON fields; nothing stored. Models in order: **Gemini 3 Flash** (free: 5/min, 20/day) →
  **Gemini 3.1 Flash Lite** (15/min) → **Gemini 3.5 Flash Lite** (15/min, 500/day), from a no-billing Google
  project; paid Gemini at launch. Temperature 0.1, fixed seed, response schema; each card checked on its own;
  changing details tagged "may vary"; links checked before showing; tap a card to expand.
- **Home banner:** the user's own last searches, up to 5 (account when signed in, the device otherwise), each with a
  live tourist-spot photo, sliding; tap to open. None yet: "No recent searches".
- **Ideas for your next trip:** every hour the AI suggests 30 varied places worldwide (beaches, mountains, heritage,
  cities, hidden gems and popular places), each confirmed on Wikipedia, kept in memory only; a random 6 every
  minute with category and "hidden gem" tags, **Save to My Trips**; tap to open.
- **My Trips:** saved places (sign-in needed).
- [x] Accounts: billing-free Google project + Gemini key, Geoapify key, Unsplash app (keys in Secret Manager)
- [x] Backend: place search, recent searches, AI cards, tourist spots and photos, random ideas, saved places
- [x] Website · [x] App · [x] Privacy Policy (stored search history, place names sent to Google for AI)

### 4.2 Wanderbook

Decided 2026-10-28.

- **Viewing:** anyone can view public profiles and posts, signed in or not (rate limited per IP against scraping);
  posting, liking, commenting and following need an account.
- **Profiles:** account name and photo, cover photo, bio, home town, counts, post grid; a random public id in links.
  **Public or private:** following a private profile sends a request the owner approves or declines; only approved
  followers see its posts and lists; making it public approves waiting requests.
- **Posts:** 1-10 photos, caption, place tag filled in from the photo's location (place, state, country; editable,
  or added by hand; opens the destination page); edit caption, delete. Likes and comments (delete your own, and
  comments on your posts).
- **Feed:** one shared timeline of the newest posts from all public profiles, private profiles you follow, and yours.
- **People search** by name.
- **Safety:** every photo and text checked by **OpenAI omni-moderation** (free) before it appears; report posts,
  comments and users; block (hides both ways, removes follows); reports emailed to the team plus an admin page on
  the website.
- **Photos:** originals, never resized or recompressed (a scrapbook of memories), shown as they are everywhere.
  Exact coordinates are never published or stored: the backend turns the photo's GPS into the place tag and removes
  the GPS from the file without touching the picture. iPhone HEIC photos become full-size JPEGs on the phone
  (browsers can't show HEIC). Uploaded one by one through the backend (checked first) into Cloudflare R2
  (`goawaygo-media`, `media.goawaygo.com`); uploads stop before the free 10 GB (about 2,000 photos) is used up.
- **Legal:** Terms (rules for user content), Community Guidelines page, Privacy Policy and Delete my data updated.
- [x] Accounts: OpenAI key, R2 bucket `goawaygo-media` and access key (Secret Manager)
- [x] Backend: profiles, follows and requests, private profiles, blocks, people search
- [x] Backend: photos and moderation, posts, likes, comments, feed, reports, admin
- [x] Website · [x] App · [x] Legal pages (Terms, Community Guidelines, Privacy Policy, Delete my data)

### 4.3 Book

Decided 2026-11-01 ("option E"): build now with what is open to a pre-launch app; each category moves to live
prices when a partner approves us, without new screens. Full comparison: the founders' findings doc.

- **Flights:** Aviasales' recent prices (Travelpayouts Data API, free): the cheapest fare on the date asked, cheaper
  dates that month, a price calendar, and "See all flights" (Aviasales' live search). Labelled "recent, may change".
- **Stays and cars:** partner cards opened on the traveller's search (no free price source exists). App: brands that
  allow apps only (Trip.com; DiscoverCars, EconomyBookings, QEEQ); website: those plus website-only brands later.
- **Links:** every partner page becomes our affiliate link (Travelpayouts links API, marker 798632, project
  597168); a partner that hasn't approved us yet gets its plain page. Commission disclosure on every results page.
- **Live prices later:** KAYAK (flights, hotels, cars; sandbox received, production pending) and Agoda (hotels;
  rejected 2026-11-07, reapply once `goawaygo.com` is the real site with crawlable travel content).
- [x] Accounts: Travelpayouts (Aviasales, EconomyBookings connected; Trip.com, DiscoverCars in review), KAYAK sandbox
- [x] Backend: airports, recent flight prices and calendar, partner links, stays and cars partners
- [x] Website · [x] App · [x] Legal pages (affiliate disclosure in Terms and Privacy Policy) · [x] Released in 1.2.0 build 8

### 4.4 AI trip planner

Decided 2026-11-08.

- **Plan:** from a destination page or a saved place in My Trips: start date, 1-10 days, travellers (solo, couple,
  family, friends), pace, budget level and interests. Each day has a morning, afternoon and evening (place, what to
  do, rough cost), food picks and a tip; plus a title, summary and trip tips.
- **Budget estimate:** about X a day for food, transport and activities and Y a night for stays, for the whole group;
  labelled an AI estimate, flights never included.
- **Change a day:** rewrite one day, optionally with a wish ("more street food"); the other days stay.
- **Share by link:** read-only page anyone can open, after an OpenAI moderation check (also for changes to a
  shared plan).
- **Booking shortcuts:** Stays and Car rentals open for the plan's place and dates; Flights opens with the destination.
- **AI:** Gemini (3.1 Flash Lite → 3.5 Flash Lite → 3 Flash), like Discover; a better model later. Signed-in users
  only: 5 new plans and 20 day changes a day per account, 50 saved plans.
- [x] Backend: plans, change a day, share, budget estimate
- [x] Website · [x] App · [x] Legal pages (trip plans in Privacy Policy and Terms) · [x] Released in 1.3.0 build 9

### 4.5 Notifications

Decided 2026-11-09.

- **Events:** Wanderbook (new follower, follow request, request accepted, likes on your post grouped per post,
  comments on your post); trip reminders (a saved plan starts in 3 days, and tomorrow); someone opened your shared
  plan (at most once a day per plan); GoAwayGo announcements (sent by an admin from the website).
- **Where:** the bell on Home lists them on the app and the website (unread count, mark as read, open the post,
  profile or plan). Push only on the Android app, through Firebase Cloud Messaging (free); no browser push.
- **Permission (Android 13+):** asked once after sign-in; if dismissed, once more at a useful moment (after following
  someone, posting or saving a plan); always a switch in Profile.
- **Settings:** a switch per type (Wanderbook, trip reminders, shared plans, announcements) turns off its push; the
  bell still lists everything. Notifications are kept 90 days.
- **How it runs:** the backend sends pushes with its own Google service account (no key file; it needs the Firebase
  Cloud Messaging role in project `goaway-483709`, then the repo variable `FCM_PROJECT`). Trip reminders: a daily
  GitHub Actions job at 09:00 IST calls the backend's reminder run. The app reads its Firebase settings from
  `env/staging.env` (public IDs; no `google-services.json`).
- [x] Backend: notifications, device tokens, settings, push, reminders, announcements
- [x] Website · [x] App · [x] Legal pages (device tokens and notifications in the Privacy Policy) · [x] Released in 1.4.0 build 10

### 4.6 Me

Decided 2026-11-09.

- **Edit your account** (User Profile): name and photo (the same as on Wanderbook), password (change it with the
  current one, or set one on a Google account), email (the new address must be confirmed by a link first; the old
  one is told), country and currency (used later to show prices in your currency, 4.7). Changing the password, the
  email or deleting the account first asks to confirm it's you: your password, or Google for accounts without one.
- **Delete account** (app and website, as Google Play requires, plus the Delete my data page): 30 days to change your
  mind. At once: signed out everywhere, pushes stopped, profile, posts and comments hidden from others, and an email
  with the date. Signing in again within 30 days keeps the account. After 30 days the daily job deletes its photo
  files, then the account and everything in it, and sends a last email.
- **Recently viewed:** places and trip plans opened (your own and shared ones), newest first, the latest 30; saved
  for signed-in accounts, on the device otherwise; can be cleared. My Trips was already done in 4.1 and 4.4.
- **Phone sign-up and sign-in with SMS: moved to MVP 2.** SMS can't be kept at $0: Firebase sends real SMS only with
  billing on, its free allowance may cover only test numbers, India costs about $0.07 a text, and Google Cloud
  budgets only alert, they can't stop charges.
- [x] Backend: account details, password, email change, delete with 30 days, daily deletion job, Recently viewed
- [x] Website · [x] App · [x] Legal pages (Delete my data, Privacy Policy)

### 4.7 Global readiness

Decided 2026-11-10.

- **Languages:** English, Hindi, Spanish, French, German, Arabic (right to left) and Japanese, on the app and the
  website. The user picks one (or the phone or browser's language is used); the apps send it as
  `Accept-Language`, and the account keeps it for pushes and emails. Translations written with AI help: **a native
  speaker checks each language before launch.** Legal pages (Privacy Policy, Terms, Delete my data, Community
  Guidelines) stay in English, the version that counts.
- **AI content** (destination cards, trip plans, ideas) is written in the user's language. Place names stay in their
  usual international form, so photos and Wikipedia checks keep working. A trip plan keeps the language it was made
  in.
- **Currencies:** prices in the user's currency everywhere (flight fares from Aviasales, trip plan costs, the
  exchange rate on destination pages). Signed in: the currency in User Profile; signed out: guessed from the phone or
  browser's region, changeable.
- **Regions and formats:** dates, numbers and prices shown the local way for the user's language and region.
- **Reviews before launch:** accessibility (WCAG 2.2 AA), speed (website bundle, images, app startup), security
  (OWASP Top 10 across the three repos, website security headers). Each ends with fixes and a short report.
- [x] Backend: languages and currencies · [x] Website · [x] App
- [x] Accessibility · [x] Speed · [x] Security · [x] Regions and formats

### Later sub-phases

A detailed checklist (and that sub-phase's decisions) is added when it starts. Notes so far:

- **Phone sign-in (MVP 2, carried over):** SMS is never free (Firebase: billing required, billed per SMS by country): cap it then.
- Wanderbook photos: R2 bucket `goawaygo-user-media`. Push: Firebase Cloud Messaging (free).

## MVP 2 — Release pipeline and website launch

Decided 2026-11-10 and 2026-11-11. The website launches for customers on `goawaygo.com` from `main`. The Android
app stays an APK download with beta testing; a store launch comes in a later MVP. The beta app uses production.
Every change ships through versions that move up through three environments, with automated and performance tests
as evidence.

| Branch        | Purpose                                   | Deploys to                                    | Merges to                       |
| ------------- | ----------------------------------------- | --------------------------------------------- | ------------------------------- |
| `feature/...` | A new feature                             | nowhere (tested inside CI)                    | → `develop` only, no approval   |
| `fix/...`     | A bug fix                                 | nowhere (tested inside CI)                    | → `develop` only, no approval   |
| `develop`     | Development: every change, a new version  | **dev** (`api-dev`, `dev.goawaygo.com`)       | → `test` only, owner's approval |
| `test`        | Testing a version before customers see it | **test** (`api-test`, `test.goawaygo.com`)    | → `main` only, owner's approval |
| `main`        | Customers                                 | **prod** (`api.goawaygo.com`, `goawaygo.com`) | —                               |

Any other branch name is rejected (Dependabot's `dependabot/...` is allowed). Pull requests are opened by the bot
account `goawaygo-bot` and approved by `goawaygoofficial`; deployments to test and prod need the same approval. The
repos are public (2026-11-11): free branch rules, protected environments and Actions minutes.

- [x] **5.1 Branches and rules** — `test` branch; branch name rules; only `feature`/`fix` → `develop` → `test` →
      `main`; approvals on `test` and `main`; branch guard as a required check.
- [x] **5.2 Versions** — every merge into `develop` makes a version (`feat:` raises the minor number, `fix:` the
      patch) with a GitHub Release and artifacts built once: the backend image and the website build. The website reads
      its settings when it starts, so one build runs in every environment.
- [x] **5.3 Three environments** — dev, test, prod on free tiers: Cloud Run ×3, Neon projects `goawaygo-staging` (dev, test) and `goawaygo-prod`, production in its own Google Cloud project `goawaygo-prod`, Cloudflare Pages
      domains, secrets per environment, Google sign-in origins, Sentry and PostHog environments. Done 2026-11-11/12: dev
      (`api-dev`, `dev.goawaygo.com`), test (`api-test`, `test.goawaygo.com`), production moved to the Google Cloud
      project `goawaygo-prod` (`api.goawaygo.com`, 2026-11-12).
- [x] **5.4 `controller.yaml`** — deploys a version to test or prod, only if it is merged there and passed AT and PT
      in the environment below; deployment status per version; rollback to an earlier version. Done 2026-11-12:
      first promotions to test and prod (backend v1.9.0, website and app v1.8.0); the AT and PT condition comes with
      5.5 and 5.6.
- [x] **5.5 Automation tests** (repo `goawaygo_automation_tests`) — API and website (Playwright), app (Maestro on an
      Android emulator); a short run on every pull request, the full run after each deployment. Done 2026-11-12/13:
      22 API, 9 website and 3 app tests pass in dev and test; read-only smoke tests in prod. They found a real bug
      (a tap between a tab's icon and label was ignored; fixed).
- [x] **5.6 Performance tests** (repo `goawaygo_performance_tests`) — API and website (k6), app start-up and
      scrolling; limits that fail the run; fake AI and partner services in dev and test. Done 2026-11-12/13: limits
      on the backend's own time (Server-Timing on test runs: reads ~3 ms, destination ~31 ms p95), Core Web Vitals,
      app start-up ~1 s and scrolling on the emulator. A version moves up only with AT and PT ✅ below.
- [ ] **5.7 Website launch** — pre-rendered public pages (search engines and partners can read them), sitemap,
      production monitoring and alerts, database backups, Google sign-in in production mode, launch checklist; then
      apply to Agoda again.
- [ ] **5.8 Product changes** — from the MVP 1 testing, added here as they are sorted.

Carried over: phone sign-in with SMS (only if it can be capped), Booking.com through Travelpayouts (late December),
KAYAK, smaller copies of Wanderbook photos, a native speaker's check of the translations.

## Phase 5 — Launch

App store launch (a later MVP) · company registration · Play Console + Android developer verification (before the
2027 rule reaches India) · production database on Cloud SQL (the website launched on Neon in MVP 2, 2026-11-13) · Google billing upgraded before the
trial ends (2027-01-14) · DPDP compliance review (substantive obligations from 13 May 2027).

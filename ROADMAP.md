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

Direction (confirmed 2026-10-25): **free resources only** until launch · **global market** (English first, every
currency) · bookings through **affiliate partners** (commission; users book on the partner's site) · Wanderbook like
Instagram · phone sign-in with SMS. Each sub-phase's own choices (providers, limits) are decided when it starts and
recorded in its section below.

| Sub-phase                 | What users get                                                                                                                                           | Status  |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| **4.0 Foundations**       | Error tracking, uptime alerts, analytics with consent, legal pages, API toolkit                                                                          | Done    |
| **4.1 Discover**          | Real destination pages (info, attractions, photos, weather, currency, visa, map), AI search, "Recently searched", rotating suggestions, Save to My Trips | Done    |
| **4.2 Wanderbook**        | Instagram-style posts with photos, comments, likes, follow, profiles (photo, bio, cover); report, block and moderation                                   | Next    |
| **4.3 Book (affiliates)** | Flights, Stays, Rentals search with prices; "Book" opens the partner; commission disclosure                                                              | Planned |
| **4.4 AI trip planner**   | Day-by-day itinerary, saved to My Trips                                                                                                                  | Planned |
| **4.5 Notifications**     | Push notifications and the bell on Home                                                                                                                  | Planned |
| **4.6 Me**                | My Trips, Recently viewed, edit profile and photo, delete account, phone sign-up and sign-in with SMS                                                    | Planned |
| **4.7 Global readiness**  | More languages, currencies and regions; accessibility, speed, security review before launch                                                              | Planned |

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

### Later sub-phases

A detailed checklist (and that sub-phase's decisions) is added when it starts. Notes so far:

- **4.3 Book:** start with Travelpayouts (free, accepts new apps: Trip.com, Kiwi.com, Agoda, DiscoverCars,
  EconomyBookings, ...); apply directly to Skyscanner, Booking.com, Expedia, KAYAK/Momondo once there is traffic
  (they ask for an existing audience). Vistara no longer exists (merged into Air India, Nov 2024).
- **4.4 AI:** a free tier that doesn't train on our data (e.g. Cloudflare Workers AI) until there is a budget.
- **4.6 Me:** SMS is never free (Firebase: ~10/day free, then billed per SMS by country): cap it during the beta.
- Wanderbook photos: R2 bucket `goawaygo-user-media`. Push: Firebase Cloud Messaging (free).

## Phase 5 — Launch

Company registration · Play Console + Android developer verification (before the 2027 rule reaches India) · production
environment (`goawaygo.com`, Cloud SQL) · Google billing upgraded before the trial ends (2027-01-14) · DPDP compliance
review (substantive obligations from 13 May 2027).

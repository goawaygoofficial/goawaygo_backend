# GoAwayGo — Backend

REST API for **GoAwayGo**, a travel app. Built with Spring Boot and PostgreSQL.

Frontends: [app](https://github.com/goawaygoofficial/goawaygo_app_frontend) (Flutter) ·
[web](https://github.com/goawaygoofficial/goawaygo_web_frontend) (React).

> **Status (beta):** accounts (email/password, Google Sign-In, email verification, password reset), health check,
> error tracking and the travel features are live in production (`api.goawaygo.com`, website `goawaygo.com`); see
> **Roadmap** (README).

## Documents in this repo

| File          | What it holds                                                                                                   |
| ------------- | --------------------------------------------------------------------------------------------------------------- |
| `README.md`   | Everything about this repo: features, how it works, setup, deployment, how we work, resources, roadmap, history |
| `LINKS.md`    | Every link for dev, test and prod, and the shared consoles                                                      |
| `RELEASES.md` | What each version brought and where it runs                                                                     |

These are the only Markdown files. Every change updates them in the same pull request (new feature, new service,
new setting, a release reaching prod).

## Contents

- [Tech stack](#tech-stack)
- [API](#api)
- [Getting started](#getting-started)
- [Database](#database)
- [Tests](#tests)
- [Secrets](#secrets)
- [Discover: destination pages, photos and ideas](#discover-destination-pages-photos-and-ideas)
- [Deployment](#deployment)
- [Releasing](#releasing)
- [How we work](#how-we-work)
- [Conventions and decisions](#conventions-and-decisions)
- [Resources and outside services](#resources-and-outside-services)
- [Booking partners](#booking-partners)
- [Roadmap](#roadmap)
- [History](#history)

## Tech stack

- Java 17
- Spring Boot 4.1 (Web MVC, Security, OAuth2 Resource Server, Data JPA, Validation)
- PostgreSQL 17 (local: Docker Compose; tests: Testcontainers)
- Flyway for database migrations
- Maven (wrapper included: `mvnw` / `mvnw.cmd`)

## API

| Method | Endpoint                        | Auth          | Purpose                                                                                            |
| ------ | ------------------------------- | ------------- | -------------------------------------------------------------------------------------------------- |
| `POST` | `/api/auth/signup`              | none          | Create an account (`name`, `email`, `password` of 8-72 characters). Returns the user, `201`.       |
| `POST` | `/api/auth/signin`              | none          | Sign in with email and password. Returns tokens.                                                   |
| `POST` | `/api/auth/refresh`             | refresh token | Exchange a refresh token for new tokens (rotation).                                                |
| `POST` | `/api/auth/logout`              | refresh token | Revoke the refresh token. `204`.                                                                   |
| `GET`  | `/api/users/me`                 | access token  | The signed-in user's own profile.                                                                  |
| `POST` | `/api/auth/google-signin`       | none          | Sign in with a Google ID token (`{"idToken": "..."}`). Returns tokens. Used by the mobile app.     |
| `POST` | `/api/auth/google-signin/code`  | none          | Sign in with a Google authorization code (`{"code": "..."}`). Returns tokens. Used by the website. |
| `POST` | `/api/auth/forgot-password`     | none          | `{"email"}` → always `202`, same body; emails a reset link if the account exists.                  |
| `POST` | `/api/auth/reset-password`      | none          | `{"token", "newPassword"}` → `204`. Verifies the email and signs out every device.                 |
| `POST` | `/api/auth/verify-email`        | none          | `{"token"}` → `204`. Marks the email verified.                                                     |
| `POST` | `/api/auth/resend-verification` | access token  | `202`: emails a new verification link (`409` if already verified).                                 |

### Discover (4.1), Wanderbook (4.2), Book (4.3) and the trip planner (4.4)

Viewing is public (rate limited per IP); everything that changes something needs an access token. Design and
reasons: [**Discover: destination pages, photos and ideas** (backend README)](**Discover: destination pages, photos and ideas** (backend README)) (Discover) and **Roadmap** (README) 4.2 (Wanderbook);
setup of photo storage, moderation and admins: [**Deployment** (README)](**Deployment** "README").

Every response speaks the request's `Accept-Language` (en, hi, es, fr, de, ar, ja; otherwise English): error
messages, the bell, AI destination cards, ideas and new trip plans. `GET /api/discover/destination` and
`POST /api/plans` also take the traveller's `currency` (the plan falls back to the account's, then INR).

| Area                    | Endpoints                                                                                                                                                                                                                                                                                                                                                                                  |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Places and destinations | `GET /api/discover/places?q=`, `GET /api/discover/destination?placeId=` or `?q=`                                                                                                                                                                                                                                                                                                           |
| Home banner and ideas   | `GET /api/discover/recent` (signed in), `POST /api/discover/photos`, `GET /api/discover/ideas`, `POST /api/discover/refresh-ideas` (public, daily job)                                                                                                                                                                                                                                     |
| My Trips                | `GET`/`POST /api/me/saved-places`, `DELETE /api/me/saved-places/{id}`                                                                                                                                                                                                                                                                                                                      |
| Account (Me)            | `GET`/`PUT /api/users/me` (`{"name","country","currency"}`), `PUT /api/users/me/language` (`{"language"}`), `GET /api/regions` (public, named in the request's language), `POST /api/auth/change-password`, `POST /api/auth/change-email` and `/confirm-email`, `POST /api/auth/delete-account` (30 days to change your mind), `POST /api/auth/purge-deleted-accounts` (public, daily job) |
| Recently viewed         | `GET`/`DELETE /api/me/recently-viewed` (places and trip plans)                                                                                                                                                                                                                                                                                                                             |
| Wanderbook profiles     | `GET`/`PUT /api/wanderbook/me`, `PUT /api/wanderbook/me/avatar` and `/cover`, `GET /api/wanderbook/profiles/{id}` (+ `/followers`, `/following`, `/posts`), `GET /api/wanderbook/people?q=`                                                                                                                                                                                                |
| Follows and blocks      | `POST`/`DELETE /api/wanderbook/profiles/{id}/follow` and `/block`, `GET /api/wanderbook/me/follow-requests`, `POST`/`DELETE …/follow-requests/{id}`, `DELETE /api/wanderbook/me/followers/{id}`, `GET /api/wanderbook/me/blocked`                                                                                                                                                          |
| Photos and posts        | `POST /api/wanderbook/photos` (multipart `file`), `POST /api/wanderbook/posts`, `GET`/`PUT`/`DELETE /api/wanderbook/posts/{id}`, `GET /api/wanderbook/feed`                                                                                                                                                                                                                                |
| Likes and comments      | `POST`/`DELETE …/posts/{id}/like`, `GET …/posts/{id}/likes`, `GET`/`POST …/posts/{id}/comments`, `DELETE /api/wanderbook/comments/{id}`                                                                                                                                                                                                                                                    |
| Reports and review      | `POST /api/wanderbook/reports`; admins: `GET /api/admin/wanderbook/reports`, `POST …/reports/{id}/resolve`                                                                                                                                                                                                                                                                                 |
| Book: flights           | `GET /api/book/airports?q=`, `GET /api/book/flights?from=&to=&depart=&return=&adults=&direct=&currency=&channel=`, `GET /api/book/flights/calendar?from=&to=&month=`                                                                                                                                                                                                                       |
| Trip plans              | `POST /api/plans` (signed in; 10-40 s), `GET /api/plans`, `GET`/`DELETE /api/plans/{id}`, `POST /api/plans/{id}/days/{n}` (`{"wish"}`), `PUT /api/plans/{id}/share`, `GET /api/plans/shared/{id}` (public)                                                                                                                                                                                 |
| Notifications           | `GET /api/notifications` (`?before=`), `GET /api/notifications/unread`, `POST /api/notifications/read` (`{"ids"}` or all), `GET`/`PUT /api/notifications/settings`, `PUT /api/notifications/devices` and `POST …/devices/remove` (`{"token","platform"}`), `POST /api/notifications/reminders` (public, daily job), `POST /api/admin/announcements` (admins)                               |
| Book: stays and cars    | `GET /api/book/stays?place=&checkin=&checkout=&adults=&rooms=&channel=`, `GET /api/book/cars?place=&airport=&pickup=&dropoff=&channel=`                                                                                                                                                                                                                                                    |

Book is public. Flight prices are Aviasales' **recent** prices (Travelpayouts Data API: cheapest fares travellers
found in the last days, labelled "may change"); stays and cars are partner links (no free price source exists). Every
partner page becomes our affiliate link through Travelpayouts' links API (`goawaygo.book.marker`, `project-id`); a
partner that hasn't approved us yet gets its plain page. `channel=app` leaves out brands that allow websites only.

### Emails: password reset and verification

- **Sign-up** sends a "verify your email" link (valid **24 h**). **Forgot password** sends a reset link (valid
  **30 min**). Links open the website: `{WEB_BASE_URL}/verify-email?token=…` and `/reset-password?token=…`.
- Links are **single-use**; tokens are 256-bit random values stored only as **SHA-256 hashes**
  (`user_action_tokens`). A new link retires the user's older unused ones of the same kind.
- **Forgot password never reveals whether an account exists**: same `202` body either way, and emails are sent
  in the background **after the transaction commits**, so response time doesn't leak it either.
- **Reset password** also marks the email verified (the link proved ownership) and **revokes every refresh token**.
- Sending is plain **SMTP** (`spring.mail.*`): **Mailpit** locally (`docker compose`, inbox at
  http://localhost:8025, nothing reaches real inboxes), **Resend** when deployed (SMTP settings in
  `application.properties`). Switching providers is configuration only.
- Delivery failures are logged (never the email body, which holds a one-time link) and not retried; users can
  request a new link.

### Google Sign-In

Two ways in, one verification path:

- **Mobile app → `/api/auth/google-signin`** with the **ID token** that Google Sign-In returns on the device.
- **Website → `/api/auth/google-signin/code`** with a one-time **authorization code** from Google's code client
  (popup mode). This lets the website use its own button instead of Google's iframe button. The backend swaps
  the code for an ID token at Google's token endpoint using the Web client's **secret** (`GOOGLE_CLIENT_SECRET`,
  `.env` only; without it this endpoint returns `503 google_sign_in_unavailable`). The browser never sees the
  secret, and the code is useless without it.

Either way, the backend then:

1. **Verifies the token**: RS256 signature against Google's published keys
   (`https://www.googleapis.com/oauth2/v3/certs`, cached), issuer `accounts.google.com`, not expired, and the
   audience is one of **our** client IDs (`goawaygo.auth.google.client-ids`: Web and iOS). Tokens issued to other
   apps are rejected.
2. **Requires `email_verified`** from Google (`403 google_email_not_verified` otherwise).
3. **Finds or creates the account**:
   - Known Google account → sign in.
   - Email already registered with a password → link Google to it. If that account's email was **never
     verified**, its password is removed and all its sessions are revoked: whoever registered it may not own the
     email (protection against pre-account takeover).
   - Email linked to a **different** Google account → `409 email_linked_to_other_google_account`.
   - New email → passwordless account, `email_verified: true`.

Only Google's verified claims are used; nothing the client sends alongside the token is trusted.

### How authentication works

- **Access token**: a signed JWT (ES256), valid **20 minutes**. Send it on every request as
  `Authorization: Bearer <accessToken>`. It is not stored on the server.
- **Refresh token**: a random value, valid **30 days**, stored on the server **only as a SHA-256 hash**.
  Every refresh returns a **new** refresh token and invalidates the old one. If an old one is used again
  (a sign it was copied), every token from that sign-in is revoked and the user must sign in again.
- **Where the refresh token goes**:
  - Mobile app: send the header `X-Client-Type: mobile`. The token comes in the JSON body; keep it in secure
    storage (Keychain / Keystore) and send it back as `{"refreshToken": "..."}`.
  - Web: the token is only set as an `HttpOnly; Secure; SameSite=Strict` cookie on `/api/auth`, which page
    JavaScript can never read. Call `/api/auth/refresh` with credentials included.

Sign-in / refresh response:

```json
{
  "user": {
    "id": 1,
    "name": "Asha",
    "email": "asha@example.com",
    "profile_picture": null,
    "phone": null,
    "email_verified": false
  },
  "accessToken": "<signed JWT>",
  "tokenType": "Bearer",
  "expiresIn": 1200,
  "refreshToken": "<only present for X-Client-Type: mobile>"
}
```

### Team-only sign-in on dev and test

dev and test are for the GoAwayGo team only (MVP 2). The environment variable `TEAM_ONLY_EMAILS` (GitHub environment
variable of the same name, comma-separated) lists the accounts that may sign up, sign in (password or Google) and
keep a session there: today only the team Gmail. That account is made an admin there. Everyone else gets
`403 team_only` ("This GoAwayGo environment is only for the GoAwayGo team"), and sessions from before end at their
next refresh. Automated and performance test runs (requests with the environment's `X-GoAwayGo-Test` token) are let
through, so the test accounts keep working. Empty in prod: everyone signs in. Code: `auth/TeamOnlyAccess`.

To give someone access later: add their email to `TEAM_ONLY_EMAILS` in the GitHub environment (dev, test or both)
and redeploy that environment.

### Errors

Every error has the same shape. Clients should branch on `error` and may show `message`:

```json
{
  "status": 401,
  "error": "invalid_credentials",
  "message": "Incorrect email or password."
}
```

Validation errors (`400`, `validation_failed`) also include `fieldErrors`, for example
`{"email": "Enter a valid email address."}`.

### Rate limits

The public endpoints are rate limited (token buckets, refilled gradually). Over the limit the API answers
`429 too_many_requests` with a `Retry-After` header (seconds).

| Endpoint                              | Limit                                                                                                                                                                              |
| ------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `signin`                              | 10 / minute per IP **and** 10 / 15 minutes per email (stops guessing spread over many IPs)                                                                                         |
| `signup`                              | 10 / hour per IP                                                                                                                                                                   |
| `google-signin`, `google-signin/code` | 20 / minute per IP                                                                                                                                                                 |
| `refresh`                             | 60 / minute per IP                                                                                                                                                                 |
| `forgot-password`                     | 5 / hour per IP, 3 / hour per email (whether or not the account exists)                                                                                                            |
| `reset-password`, `verify-email`      | 20 / minute per IP                                                                                                                                                                 |
| `resend-verification`                 | 3 / hour per user                                                                                                                                                                  |
| Discover                              | search 60 / minute, destination pages 10 / 10 minutes, photos 20 / minute, ideas 30 / minute per IP                                                                                |
| Trip planner                          | 5 new plans / day and 20 day changes / day per user; shared plans 60 / minute per IP                                                                                               |
| Book                                  | airport search 60 / minute, flights and calendars 30 / minute, stays and cars 30 / minute per IP                                                                                   |
| Wanderbook                            | views 120 / minute and people search 60 / minute per IP; follows, blocks and edits 60 / minute, uploads 100 / day, posts, comments and likes 120 / hour, reports 20 / day per user |

Limits are settings (`goawaygo.rate-limit.*` in `application.properties`); `RATE_LIMIT_ENABLED=false` turns them
off. They are kept **in memory per backend instance**; with several instances, move them to a shared store
(Bucket4j supports Redis and PostgreSQL). The client IP is the connection address. `X-Forwarded-For` is not
trusted, so behind a reverse proxy configure Spring's forwarded-header support for the proxy.

## Getting started

### Prerequisites

- JDK 17+
- Docker Desktop (runs the local database and the tests' database)

### 1. Clone

```bash
git clone https://github.com/goawaygoofficial/goawaygo_backend.git
cd goawaygo_backend
```

### 2. Create `.env`

Copy `.env.example` to `.env` and fill in the three database passwords and the JWT key pair
(the file explains how to generate them). `.env` is gitignored, never commit it.

### 3. Start the database

```bash
docker compose up -d
```

This starts PostgreSQL 17 on `127.0.0.1:5433` (only reachable from your machine) and **Mailpit**, which catches
every email the backend sends: open **http://localhost:8025** to read them. On first start it creates the
`goawaygo` database and its roles (see [Database](#database)).

### 4. Run the backend

```bash
./mvnw spring-boot:run        # Windows PowerShell: .\mvnw.cmd spring-boot:run
```

Flyway applies any pending migrations, then the API starts at `http://localhost:8080`.

## Database

### Roles (least privilege)

| Role             | Used by              | Can                                                           |
| ---------------- | -------------------- | ------------------------------------------------------------- |
| `postgres`       | Container setup only | Everything (superuser). Never used by the backend.            |
| `goawaygo_owner` | Flyway migrations    | Create / alter / drop tables in the `goawaygo` database       |
| `goawaygo_app`   | The running backend  | Read and write data. **Cannot** create, alter or drop tables. |

Created by [`docker/postgres/init/01-create-roles.sh`](docker/postgres/init/01-create-roles.sh). The tests use the
same script, and `DatabaseSetupTests` verifies these permissions.

### Migrations

Schema changes are SQL files in `src/main/resources/db/migration`, named `V<number>__<description>.sql`
(for example `V2__create_users.sql`). Flyway applies them in order at startup.
**Never edit a migration that has been merged**; add a new one instead.

### Connect with pgAdmin

Add a server: **Host** `localhost`, **Port** `5433`, **Database** `goawaygo`, **Username** `goawaygo_owner`
(or `goawaygo_app` for read/write-only), **Password** from your `.env`.

### Useful commands

```bash
docker compose ps             # status
docker compose logs postgres  # database logs
docker compose down           # stop (data is kept)
docker compose down -v        # stop and DELETE all local data (fresh database on next start)
```

## Tests

```bash
./mvnw verify                 # build + all tests
```

Tests start their own throwaway PostgreSQL container (Docker must be running), so they never touch your local data.

## Secrets

Never put passwords, keys or tokens in `application.properties`. Locally they live in `.env`; deployed
environments set the same keys (`DB_APP_PASSWORD`, `DB_OWNER_PASSWORD`, ...) as environment variables.

## Discover: destination pages, photos and ideas

How the backend builds a destination page, the home banner and "Ideas for your next trip", and why each choice was
made. The prompts themselves are in `src/main/resources/prompts/` (edit them there; they are read at start-up).

### Endpoints

| Endpoint                                            | Who                  | What                                                                                        |
| --------------------------------------------------- | -------------------- | ------------------------------------------------------------------------------------------- |
| `GET /api/discover/places?q=`                       | public               | Up to 6 real places while the user types (Geoapify)                                         |
| `GET /api/discover/destination?placeId=` (or `?q=`) | public, JWT optional | The whole page: place, 9 cards, spot photos. Signed in: added to the user's recent searches |
| `GET /api/discover/recent`                          | signed in            | The user's last 5 searched places, each with a live photo (home banner)                     |
| `POST /api/discover/photos`                         | public               | The same for a signed-out user, whose last 5 searches stay on their device                  |
| `GET /api/discover/ideas?count=6`                   | public               | Random ideas for the next trip; the app asks again every minute                             |
| `GET/POST/DELETE /api/me/saved-places`              | signed in            | My Trips (up to 500 saved places)                                                           |

The destination page is returned only when everything is ready, so the website and app show their travel animation
until then and then the whole page at once (usually 10-20 s on the free AI tier; Cloud Run's timeout is 120 s).

### 1. The 9 info cards (AI)

**One request per page open** asks for all 9 cards; the answer is JSON with **one separate field per card**
(Gemini's `responseSchema`), so each card is checked and shown on its own. Nothing is stored: every page open asks
again.

| Card id       | Title                | Subtitle                                                               |
| ------------- | -------------------- | ---------------------------------------------------------------------- |
| `quickFacts`  | Quick Facts          | Country, languages, currency, time zone and what it's known for        |
| `visa`        | Visa & Entry Rules   | Visa systems, passport validity and entry requirements                 |
| `seasons`     | Best Time to Visit   | Peak, off-peak and shoulder months, weather and big events             |
| `attractions` | Top Attractions      | Famous landmarks and experiences worth your time                       |
| `hotels`      | Best Areas to Stay   | Neighbourhoods to book in, and who each one suits                      |
| `cuisine`     | Food & Dining        | Dishes to try and local dining customs                                 |
| `transport`   | Getting Around       | From the airport and the main ways to travel locally                   |
| `insurance`   | Travel Insurance     | Whether it's required, medical care and local risks                    |
| `currency`    | Money & Connectivity | Currency, cards or cash, tipping, SIM/eSIM, plugs and emergency number |

Prompts: `destination-cards-system.txt` (accuracy rules, the same every time) and `destination-cards-request.txt`
(the destination block, the only part that changes, plus each card's instructions).

#### Models (tested 2026-10-26, Gemini API free tier, key from a no-billing project so it can never be charged)

| Order | Model                                    | Free limits           | Test result (3 destinations)          |
| ----- | ---------------------------------------- | --------------------- | ------------------------------------- |
| 1st   | `gemini-3-flash-preview` (thinking: low) | 5/min, 20/day         | 3/3 correct, 10-14 s, richest answers |
| 2nd   | `gemini-3.1-flash-lite`                  | 15/min, about 500/day | 3/3 correct, 7-13 s                   |
| 3rd   | `gemini-3.5-flash-lite`                  | 15/min, 500/day       | correct but slow, 20-53 s             |

A model that is at its limit, overloaded or failing is skipped and the next one asked, all within 100 s
(`goawaygo.discover.ai-deadline`). If none can answer, the page still opens with its photos and says the cards are
busy. Each model is its own provider (`gemini`, `geminilite`, `geminilite35`) with a daily limit just below Google's,
all using the one Gemini key. Not used: Gemini 3.6/3.8 Flash (overloaded, timeouts on free tier), Gemma 4
(unreliable JSON), 2.5 models (retired). At launch: paid Gemini by changing settings only.

Gemini 3 models think before answering; thinking counts against the output limit, so the request allows 16,000
output tokens and asks for low thinking, otherwise the answer is cut off.

#### Why repeated searches don't drift into made-up answers

- Every request is **independent**: no chat history and no previous answers, so one search can't influence the next.
- **Temperature 0.1** and a **fixed seed**: the most likely (most factual) wording, the same each time.
- **Structured output**: the model must answer with exactly the 9 card fields.
- **"Leave it out if unsure"** rules in the prompt, and backend checks on every answer (below).

#### What the backend checks (each card separately, `DestinationCards.check`)

- `unknownDestination: true` → the page says the place isn't known (no cards, no photos).
- At most 7 items; labels at most 6 words, details at most 35 (slack over the prompt's 4 and 25); markdown removed.
- **Links**: only `https://` addresses in the `link` field, each opened by the backend and shown only if it answers
  within 3 s; web addresses inside the text are removed.
- **Changing details** (prices, currency amounts, rates, times, years, percentages) are tagged `mayChange` even if
  the AI forgot. The apps show "may vary, check before you go" on those items.
- A card with nothing valid shows "Not available right now"; the other cards still show.

### 2. Spot photos (banner of the destination page)

Fetched live on every request and never stored. Up to 8 spots:

1. The AI's **Top Attractions** names (up to 6) → each spot's own **Wikipedia** article photo, accepted only if the
   article has the same name (`NameMatch`: "Musée d'Orsay station" is not "Musée d'Orsay") and lies near the place
   (60 km for a city, 400 km for a region, 2,500 km for a country). Unsplash if Wikipedia has none.
2. At the same time as the AI request, **Wikidata** lists well-known spots near the place (tourist attractions,
   monuments, temples, beaches, ...; not for countries). Up to 4 not already named by the AI → **Unsplash**, accepted
   only if the photo's own description mentions the spot; Wikipedia if Unsplash has none.
3. No spot found (a village): a photo of the place itself.

Every photo carries its credit as both sources require: Wikimedia Commons photographer and licence
("Photo: Jane Doe, CC BY-SA 4.0, Wikimedia Commons", linked to the file page), or "Photo by X on Unsplash" (linked to
the photographer with Unsplash's referral parameters). Unsplash is a demo app (50 requests an hour) until it is
approved for production.

### 3. Home banner (recent searches)

Only the user's own last 5 searched places, newest first. Signed in: kept in `search_history` (place and its top
spot, deleted with the account). Signed out: kept on the device and sent to `POST /photos`. Photos are looked up live
each time. No searches yet: the apps show "No recent searches".

### 4. Ideas for your next trip

Once an hour the AI (`gemini-3.1-flash-lite`, then `gemini-3.5-flash-lite`; prompts `ideas-system.txt`,
`ideas-request.txt`) suggests 30 varied real places from every continent: beaches, mountains, heritage, cities,
popular places and hidden gems, avoiding the previous hour's names. Each place is kept only if Wikipedia confirms it
(same name, a location and a photo). The list lives in memory only; each request gets a random 6, and the apps
refresh them every minute and offer **Save to My Trips**. If the AI can't answer, the previous list keeps being used
(right after a restart there are no ideas until the AI answers).

### Privacy

The AI prompt contains only the place (name, type, region, country, coordinates), never anything about the user.
Free-tier Gemini requests may be used by Google to improve its products, acceptable for place names. The Privacy
Policy lists: search history kept for signed-in users (last 5); place names sent to Google Gemini; place search by
Geoapify; photos from Wikimedia and Unsplash (the user's browser loads the images from them).

### Rate limits per IP

Search 60 a minute, destination pages 10 per 10 minutes (each costs an AI request), photos 20 a minute, ideas 30 a
minute (`goawaygo.discover.limits.*`).

## Deployment

How the GoAwayGo backend runs outside a developer machine. Pre-launch (alpha/beta) everything here is free; at
launch the database moves to Google Cloud SQL (Phase 5).

### Architecture

| Part             | Service                                                  | Address                                                                    |
| ---------------- | -------------------------------------------------------- | -------------------------------------------------------------------------- |
| Backend          | Google Cloud Run, `asia-southeast1` (Singapore)          | `api.goawaygo.com` (prod), `api-test.goawaygo.com`, `api-dev.goawaygo.com` |
| Database         | Neon PostgreSQL 17, AWS Singapore (free plan)            | private (TLS only)                                                         |
| Secrets          | Google Secret Manager                                    | read by the backend only                                                   |
| Container images | Google Artifact Registry `goawaygo` (the newest 10 kept) | —                                                                          |
| DNS              | Cloudflare (`goawaygo.com`)                              | —                                                                          |
| Email            | Resend over SMTP                                         | `no-reply@goawaygo.com`                                                    |

Why Singapore: Cloud Run can attach a custom domain directly there (not in Mumbai, which would need a paid load
balancer), and Neon has a Singapore region next to it. Users in India are ~50–70 ms away.

```
merge into develop ─► CI (tests) ─► "Release": version vX.Y.Z ─► image …/backend:vX.Y.Z (built once) ─► dev
                                                                         │
                     controller.yaml (5.4): the same image ──────────────┴─► test ─► prod
                                    Flyway migrates each environment's database when its service starts
```

### How a deploy works

`.github/workflows/release.yml` runs after CI passes on `develop` (or by hand: Actions → Release → Run workflow). It
makes the version and its GitHub Release, builds the [Dockerfile](../Dockerfile) once and pushes
`…/goawaygo/backend:vX.Y.Z`, then calls `.github/workflows/deploy.yml` for **dev**. `deploy.yml` deploys one version
to one environment (dev, test or prod); test and prod are deployed by `controller.yaml` (5.4). A deploy:

1. Gets short-lived Google credentials through Workload Identity Federation. **No Google key is stored in GitHub.**
2. Checks that the version's image exists, and deploys it to that environment's Cloud Run service with its plain
   settings as environment variables and its secrets from Secret Manager.
3. Checks that `GET /api/users/me` answers 401 (the app is up and security is on).

On start the backend runs any new Flyway migrations as `goawaygo_owner`, then serves requests as `goawaygo_app`.

Cloud Run settings and why:

- `min-instances 0`: free while nobody uses it. The first request after ~15 idle minutes waits for a cold start
  (~7–10 s for Spring Boot); later requests are fast.
- `max-instances 1`: rate limits are kept in memory, so they only hold with a single instance.
- `cpu-throttling` (request-based billing): CPU is given, and billed, only while a request is being handled. An
  instance kept up between uptime checks costs nothing. So nothing may run after the response: emails are sent inside
  the request (`goawaygo.mail.async=false`), and forgot-password always takes at least 1.5 s so its timing doesn't
  reveal whether the account exists.
- `cpu-boost`: extra CPU during startup, shorter cold starts.

### Rollback

Every deploy is a Cloud Run revision. To go back to the previous one:

```bash
gcloud run revisions list --service goawaygo-backend --region asia-southeast1
```

```bash
gcloud run services update-traffic goawaygo-backend --region asia-southeast1 --to-revisions REVISION_NAME=100
```

Database migrations are not rolled back automatically. Migrations should be additive (new columns/tables) so the
previous revision keeps working.

### Environments (MVP 2, 5.3)

Naming: **staging** means non-production (dev and test), **prod** means production, everywhere. What ran before the
split became **prod**: same service, database, secrets and `api.goawaygo.com`, so the beta app and the testers' accounts
carry on. dev and test are new and start empty (Flyway creates their tables).

- Neon: project `goawaygo-prod` (renamed from the first `goawaygo-staging`) holds `goawaygo`; project
  `goawaygo-staging` holds `goawaygo_dev` and `goawaygo_test`.
- Google Cloud: project `goawaygo-staging` runs dev and test and keeps the image registry (images are built once);
  production runs in its own project `goawaygo-prod` (moved 2026-11-12, same address).

|                   | dev                                       | test                         | prod                              |
| ----------------- | ----------------------------------------- | ---------------------------- | --------------------------------- |
| Gets              | every version (Release)                   | a version from dev (5.4)     | a version from test (5.4)         |
| Approval          | none                                      | the owner                    | the owner                         |
| Cloud Run service | `goawaygo-backend-dev`                    | `goawaygo-backend-test`      | `goawaygo-backend`                |
| API               | `api-dev.goawaygo.com`                    | `api-test.goawaygo.com`      | `api.goawaygo.com`                |
| Website           | `dev.goawaygo.com`                        | `test.goawaygo.com`          | `goawaygo.com` (+ `www`)          |
| Pages project     | `goawaygo-web-dev`                        | `goawaygo-web-test`          | `goawaygo-web-prod`               |
| Google Cloud      | `goawaygo-staging`                        | `goawaygo-staging`           | `goawaygo-prod`                   |
| Neon project      | `goawaygo-staging`                        | `goawaygo-staging`           | `goawaygo-prod`                   |
| Database          | `goawaygo_dev`                            | `goawaygo_test`              | `goawaygo`                        |
| Roles             | `goawaygo_dev_owner`/`_app`               | `goawaygo_test_owner`/`_app` | `goawaygo_owner`/`_app`           |
| Own secrets       | `goawaygo-dev-db-*`, `goawaygo-dev-jwt-*` | `goawaygo-test-…`            | `goawaygo-db-*`, `goawaygo-jwt-*` |

Shared by all three: the Google client secret, Resend, R2 and the outside API keys (the AI's free daily quota too),
Firebase. Photos (R2) are on in prod only for now: dev and test refuse photo uploads (`WANDERBOOK_PHOTOS` unset).

Each environment is a **GitHub environment** of the same name in the backend and website repos, holding **everything**
that environment deploys with (nothing is inherited from a repository variable of the same name):

| Variable (backend)                                    | dev                                        | test                            | prod                                            |
| ----------------------------------------------------- | ------------------------------------------ | ------------------------------- | ----------------------------------------------- |
| `GCP_PROJECT_ID`                                      | `goawaygo-staging`                         | `goawaygo-staging`              | `goawaygo-prod`                                 |
| `GCP_WIF_PROVIDER`, `GCP_DEPLOY_SA`, `GCP_RUNTIME_SA` | goawaygo-staging's (`gcp-bootstrap.sh`)    | same as dev                     | goawaygo-prod's                                 |
| `SERVICE`                                             | `goawaygo-backend-dev`                     | `goawaygo-backend-test`         | `goawaygo-backend`                              |
| `SECRET_PREFIX`                                       | `goawaygo-dev`                             | `goawaygo-test`                 | `goawaygo`                                      |
| `DB_HOST`                                             | the dev/test database host                 | the dev/test database host      | the prod database host                          |
| `DB_NAME`, `DB_OWNER_USER`, `DB_APP_USER`             | `goawaygo_dev`, `…_dev_owner`, `…_dev_app` | `goawaygo_test`, `…`            | `goawaygo`, `goawaygo_owner`, `goawaygo_app`    |
| `CORS_ALLOWED_ORIGINS`                                | `https://dev.goawaygo.com`                 | `https://test.goawaygo.com`     | `https://goawaygo.com,https://www.goawaygo.com` |
| `WEB_BASE_URL`                                        | `https://dev.goawaygo.com`                 | `https://test.goawaygo.com`     | `https://goawaygo.com`                          |
| `API_BASE_URL`                                        | `https://api-dev.goawaygo.com`             | `https://api-test.goawaygo.com` | `https://api.goawaygo.com`                      |
| `SENTRY_ENVIRONMENT`                                  | `dev`                                      | `test`                          | `production`                                    |
| `TEST_FAKES`, `WANDERBOOK_PHOTOS`                     | `true`, `false`                            | `true`, `false`                 | `false`, `true`                                 |
| `TEAM_ONLY_EMAILS`                                    | the team Gmail                             | the team Gmail                  | (empty: everyone)                               |

The environment `backup` (database backups) holds prod's `GCP_PROJECT_ID`, `GCP_WIF_PROVIDER`, `GCP_DEPLOY_SA` and
`DB_HOST`, and `BACKUP_PUBLIC_KEY`. Repository variables are only what every environment shares (section 4 below).
Website environments hold `PAGES_PROJECT` and `WEB_URL`.

Setting up dev or test (once each; `<env>` is `dev` or `test`):

1. Database: `GOAWAYGO_ENV=<env> scripts/setup-hosted-db.sh goawaygo-staging` (asks for the host and admin
   password; creates `goawaygo_<env>` and its roles, stores their passwords as `goawaygo-<env>-db-*-password`).
2. Login keys: `GOAWAYGO_ENV=<env> scripts/gcp-secrets.sh goawaygo-staging jwt`, then
   `GOAWAYGO_ENV=<env> scripts/gcp-secrets.sh goawaygo-staging access`.
3. The GitHub environment `<env>` with the variables above.
4. After its first deploy: make it public, map `api-<env>.goawaygo.com` (steps 5 and 6 below, with the service
   `goawaygo-backend-<env>`).

#### Production in its own project (5.3 part C, done 2026-11-12)

prod moved from `goawaygo-staging` to the Google Cloud project `goawaygo-prod`, so production has its own
permissions, logs and secrets. The database (Neon `goawaygo-prod`), the image and `api.goawaygo.com` stay the same.
Images are still built once, in `goawaygo-staging` (repository variable `IMAGE_PROJECT_ID=goawaygo-staging`); prod's
deployer and Cloud Run may read them.

1. Create the project `goawaygo-prod` and link the billing account (done 2026-11-11).
2. Set it up, reading images from `goawaygo-staging` and sending pushes through Firebase:

   ```bash
   IMAGES_FROM=goawaygo-staging FCM_PROJECT=goaway-483709 \
     GITHUB_REPO_ID="$(gh api repos/goawaygoofficial/goawaygo_backend --jq .id)" scripts/gcp-bootstrap.sh goawaygo-prod
   ```

3. Copy production's secrets (same values, so nobody is signed out; never printed):
   `scripts/gcp-copy-secrets.sh goawaygo-staging goawaygo-prod`.
4. The GitHub environment **prod** gets the four values the setup prints: `GCP_PROJECT_ID`, `GCP_WIF_PROVIDER`,
   `GCP_DEPLOY_SA`, `GCP_RUNTIME_SA` (dev and test have goawaygo-staging's).
5. The next prod deploy (Controller, after a merge into `main`) creates `goawaygo-backend` in `goawaygo-prod`. Make it
   public (step 5 below with `--project goawaygo-prod`) and check it on its `run.app` address.
6. Move the address (the app is offline until Google issues the certificate, ~15–60 minutes; warn the testers):

   ```bash
   gcloud beta run domain-mappings delete --domain api.goawaygo.com --region asia-southeast1 --project goawaygo-staging
   gcloud beta run domain-mappings create --service goawaygo-backend --domain api.goawaygo.com --region asia-southeast1 --project goawaygo-prod
   ```

   The Cloudflare record (CNAME `api` → `ghs.googlehosted.com`) stays.

7. Done 2026-11-14: the old service `goawaygo-backend` and prod's copies of its secrets (`goawaygo-db-*`,
   `goawaygo-jwt-*`, `goawaygo-r2-*`) are deleted from `goawaygo-staging`. The shared keys stay there for dev and
   test; change a shared key in both projects.

### One-time setup (per environment)

Done once by a project owner. Commands run from the `backend` folder in Git Bash. Nothing below prints a secret.

#### 1. Google Cloud project

1. Create the project (e.g. `goawaygo-staging`) with billing (free trial) and a budget alert
   (Billing → Budgets & alerts, ₹100, alerts at 50/90/100%).
2. Install the gcloud CLI and sign in: `gcloud auth login`.
3. Run:

   ```bash
   GITHUB_REPO_ID="$(gh api repos/goawaygoofficial/goawaygo_backend --jq .id)" scripts/gcp-bootstrap.sh goawaygo-staging
   ```

   Deploys are tied to the repository's numeric ID, so renaming the repo or the GitHub account doesn't break them.

   It enables the APIs, creates the image repository, the two service accounts and Workload Identity Federation,
   and prints the GitHub variables for step 4.

#### 2. Database (Neon)

1. In Neon create the project: PostgreSQL 17, region AWS Asia Pacific (Singapore). `goawaygo-prod` for prod,
   `goawaygo-staging` for dev and test (Environments above).
2. Run (asks for the host and the admin password; Neon → Dashboard → Connect, use the host **without** `-pooler`):

   ```bash
   scripts/setup-hosted-db.sh goawaygo-staging
   ```

   It creates the `goawaygo` database with the `goawaygo_owner` / `goawaygo_app` roles (same script as local
   development) and stores their new random passwords in Secret Manager.

#### 3. Secrets

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

| Secret Manager name             | Becomes                | Source                                        |
| ------------------------------- | ---------------------- | --------------------------------------------- |
| `goawaygo-db-owner-password`    | `DB_OWNER_PASSWORD`    | generated by `setup-hosted-db.sh`             |
| `goawaygo-db-app-password`      | `DB_APP_PASSWORD`      | generated by `setup-hosted-db.sh`             |
| `goawaygo-jwt-private-key`      | `JWT_PRIVATE_KEY`      | generated by `gcp-secrets.sh jwt`             |
| `goawaygo-jwt-public-key`       | `JWT_PUBLIC_KEY`       | generated by `gcp-secrets.sh jwt`             |
| `goawaygo-google-client-secret` | `GOOGLE_CLIENT_SECRET` | Google Auth Platform → Clients → GoAwayGo Web |
| `goawaygo-resend-api-key`       | `MAIL_PASSWORD`        | Resend → API Keys                             |

#### 4. GitHub repository variables

Settings → Secrets and variables → Actions → **Variables** (identifiers and URLs, not secrets). Only what every
environment shares; each environment's own values are in its GitHub environment (Environments above).

| Variable                                | Value                                                                         |
| --------------------------------------- | ----------------------------------------------------------------------------- |
| `GCP_REGION`                            | `asia-southeast1`                                                             |
| `IMAGE_PROJECT_ID`                      | `goawaygo-staging` (the one image registry; Release builds there)             |
| `IMAGE_WIF_PROVIDER`, `IMAGE_DEPLOY_SA` | goawaygo-staging's Workload Identity provider and deployer (they push images) |
| `PROD_API_BASE_URL`                     | `https://api.goawaygo.com` (the daily jobs call prod)                         |
| `PROD_WEB_URL`                          | `https://goawaygo.com` (the prod smoke tests check the API allows it)         |
| `GOOGLE_CODE_FLOW_ENABLED`              | `true` once `goawaygo-google-client-secret` exists                            |
| `MAIL_FROM`                             | `GoAwayGo <no-reply@goawaygo.com>` once Resend is set up (empty = no mail)    |
| `FCM_PROJECT`                           | `goaway-483709` once the runtime account may send pushes (empty = no push)    |
| `EXTERNAL_API_PROVIDERS`                | the outside APIs in use (`gemini geoapify unsplash openai travelpayouts`)     |
| `SENTRY_DSN`                            | the goawaygo-backend project's DSN                                            |
| `CLOUDFLARE_ACCOUNT_ID`                 | for the backup upload to R2                                                   |

Release stays inactive until `IMAGE_PROJECT_ID` is set; a deploy needs its environment's `GCP_PROJECT_ID`.

#### 5. Make the API public (once, after the first deploy)

The first deploy succeeds but every request gets Google's 401/403 until the service allows public calls. The deployer
account can't change that on purpose (least privilege), so a project owner runs once:

```bash
gcloud run services add-iam-policy-binding goawaygo-backend --region asia-southeast1 --member allUsers --role roles/run.invoker
```

Then re-run the workflow (Actions → Release → Run workflow) to get a green smoke test. The API's own
security (JWT, rate limits) protects every endpoint; this setting only lets requests reach it.

#### 6. Custom domain `api.goawaygo.com`

1. Verify the domain with Google once: `gcloud domains verify goawaygo.com` opens Search Console; add the TXT
   record it shows in Cloudflare DNS.
2. After the first deploy:

   ```bash
   gcloud beta run domain-mappings create --service goawaygo-backend --domain api.goawaygo.com --region asia-southeast1
   ```

3. In Cloudflare DNS add the record it prints (CNAME `api` → `ghs.googlehosted.com`) as **DNS only** (grey cloud),
   so Google can issue the certificate. HTTPS is ready after up to ~15 minutes.

#### 7. After the first deploy: check client IPs

Rate limits use the client address from `X-Forwarded-For`, trusted only from internal proxy addresses
(`FORWARD_HEADERS_STRATEGY=native`). Check once that the backend sees real client IPs, not the proxy's: with
`logging.level.org.apache.catalina.valves.RemoteIpValve=DEBUG` temporarily, or by checking that two people on
different networks don't share sign-in limits. If it sees a proxy address, set `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES`
to match it.

### Database backups (5.7)

Every night at 02:00 India time, **Database backup** (`.github/workflows/backup.yml`) dumps the prod database
(`pg_dump`, custom format), encrypts it for the owner and uploads it to the Cloudflare R2 bucket `goawaygo-backups`
(`prod/goawaygo-prod-<date>.tar`). A bucket rule deletes copies after 30 days. Neon's own restore window is short;
these copies cover mistakes found later.

- **Encrypted for the owner only:** a fresh AES-256 key per copy, itself encrypted with the owner's public key
  (repository variable `BACKUP_PUBLIC_KEY`). The private key is on the owner's PC (`~/.goawaygo/backup/`), with an
  offline copy; GitHub and R2 can't read a copy.
- **No stored database password:** the job signs in to goawaygo-prod keyless (GitHub environment `backup`) and reads
  `goawaygo-db-owner-password` from Secret Manager.

Set up once (owner):

1. Key pair: `scripts/backup-keys.sh` (gh signed in as the owner). Copy `~/.goawaygo/backup/backup-private.pem`
   somewhere safe offline.
2. Cloudflare → R2 → **Create bucket** `goawaygo-backups` (location: Asia-Pacific) → **Settings → Object lifecycle
   rules → Add rule**: delete objects 30 days after upload. R2 → **Manage API tokens → Create API token**: Object
   Read & Write, only bucket `goawaygo-backups`; store its two values as the backend repository secrets
   `R2_BACKUP_ACCESS_KEY_ID` and `R2_BACKUP_SECRET_ACCESS_KEY` (`gh secret set ... -R goawaygoofficial/goawaygo_backend`,
   pasted into the hidden prompt).
3. Let the prod deployer read the database owner password (the job runs as it):

   ```bash
   gcloud secrets add-iam-policy-binding goawaygo-db-owner-password --project goawaygo-prod --member serviceAccount:goawaygo-deployer@goawaygo-prod.iam.gserviceaccount.com --role roles/secretmanager.secretAccessor
   ```

4. Actions → Database backup → **Run workflow**; the copy appears in R2.

**Restore** (practice it once, then whenever needed): download the copy from R2, then
`scripts/restore-backup.sh <file>.tar` (Docker running). It decrypts with the private key, checks the checksum and
loads the dump into the database you name: a scratch database (e.g. `goawaygo_restore_test` in the Neon project
`goawaygo-staging`) for practice; prod's own `goawaygo` only on purpose (it asks you to type "replace prod").

### Costs and limits (pre-launch)

- Cloud Run free tier (request-based billing): 180,000 vCPU-seconds, 360,000 GiB-seconds and 2 million requests a
  month, counted only while requests are handled. Uptime checks every 3 minutes use well under 1% of it.
- Neon free: ~1 GB storage, 100 compute hours a month; it sleeps after 5 idle minutes and wakes in under a second.
  To let it sleep, the connection pool keeps no idle connections (`minimum-idle=0`, closed after 2 minutes) and the
  health check never touches the database.
- Artifact Registry: 0.5 GB free; the cleanup policy keeps the newest 3 images (they share most layers).
- **The Google free trial ends 90 days after sign-up.** Upgrade the billing account to paid before then or the
  project is stopped. Usage inside the free tier stays $0; the budget alert warns before any charge.

### Health and uptime

`GET https://api.goawaygo.com/actuator/health` answers `{"status":"UP"}` without signing in. It only says the backend
is running: it doesn't check the database or email (so uptime checks don't wake Neon); those failures reach Sentry.
Nothing else from Spring Boot Actuator is exposed.

Better Stack (free plan, team Gmail) checks every 3 minutes and emails on downtime:

| Monitor                                              | Expect             | Every  |
| ---------------------------------------------------- | ------------------ | ------ |
| `https://api.goawaygo.com/actuator/health`           | status 200, `"UP"` | 3 min  |
| `https://goawaygo.com/home`, keyword "GoAwayGo"      | status 200         | 3 min  |
| `https://downloads.goawaygo.com/android/latest.json` | status 200         | 30 min |

The API monitor allows a 30 s timeout, because the first check after an idle period waits for a cold start. Public
status page: `status.goawaygo.com` (CNAME in Cloudflare, DNS only).

### Outside APIs

Every call to an outside API (places, photos, flights, AI, ...) goes through `com.goawaygo.backend.external`:
timeouts, retries for temporary failures, a circuit breaker that pauses a failing provider for a minute, per-day and
per-month usage limits kept in the database (`external_api_usage`) that refuse calls before a free tier is exceeded,
and a database cache (`external_api_cache`) that also serves the last answer when the provider is down. When nothing
can be returned, the client gets a 503 with a friendly message.

To add a provider:

1. Sign up with the team Gmail; note the free tier's limits.
2. Add its settings to `application.properties`, limits **below** the free tier:

   ```properties
   goawaygo.external.providers.opentripmap.base-url=https://api.opentripmap.com/0.1
   goawaygo.external.providers.opentripmap.daily-limit=4500
   goawaygo.external.providers.opentripmap.cache-ttl=7d
   ```

   Names are lowercase letters and digits only. Optional: `connect-timeout` (3s), `read-timeout` (10s),
   `max-attempts` (3), `retry-wait` (500ms), `monthly-limit`.

3. If it needs a key, store it in Secret Manager (typed hidden; the backend can read it right away):

   ```bash
   scripts/gcp-secrets.sh goawaygo-staging provider opentripmap
   ```

4. Add the name to the repo variable `EXTERNAL_API_PROVIDERS` (space-separated, e.g. `opentripmap unsplash`). The deploy
   passes the key as `GOAWAYGO_EXTERNAL_PROVIDERS_<NAME>_APIKEY`. Locally, put that variable in `.env`.
5. Use it from a feature: `externalApis.client("opentripmap").cached(key, Type.class, (http, apiKey) -> ...)`.

Providers in use (4.1 Discover, **Discover: destination pages, photos and ideas** (backend README)): `gemini`, `geminilite`, `geminilite35` (one per AI
model, all with the one Gemini key, secret `goawaygo-api-gemini`), `geoapify` (`goawaygo-api-geoapify`), `unsplash`
(`goawaygo-api-unsplash`), and `wikipedia`, `commons`, `wikidata` (no key). `EXTERNAL_API_PROVIDERS` is
`gemini geoapify unsplash`. Destination pages can take up to a minute on the free AI tier, so Cloud Run's request
timeout is 120 s (`deploy.yml`).

Calls so far are in the database: `SELECT * FROM external_api_usage ORDER BY period_start DESC;`. Reaching a limit
or a provider failing repeatedly is logged as an error, so it shows up in Sentry.

### Wanderbook photos, moderation and reports (4.2)

- **Photos** go to the Cloudflare R2 bucket `goawaygo-media` (public at `media.goawaygo.com`) through the backend,
  which keeps the original file and only removes its location data. Setup: create an R2 API token (Object Read &
  Write, bucket `goawaygo-media` only), store it with `scripts/gcp-secrets.sh goawaygo-staging r2`, then set the repo
  variable `WANDERBOOK_PHOTOS` to `true` and redeploy. Without it, uploads answer "Photos can't be saved right now".
  Uploads stop at 9.5 GB in total (`goawaygo.media.max-total-bytes`) to stay inside the free 10 GB.
- **Moderation**: OpenAI omni-moderation checks every photo and text (`openai` provider; key `goawaygo-api-openai`,
  add `openai` to `EXTERNAL_API_PROVIDERS`). If it can't answer, nothing is posted (users are asked to try again).
- **Reports** are emailed to `team@goawaygo.com` and reviewed by admins on the website (`/admin/reports`). Make
  someone an admin in the Neon SQL editor: `UPDATE users SET is_admin = true WHERE email = 'name@example.com';`

### Notifications (4.5)

- **The bell** works without any setup: notifications are saved in Postgres (`notifications`, kept 90 days) and
  listed by `/api/notifications`.
- **Push** (Android) goes through Firebase Cloud Messaging in the Firebase project `goaway-483709`, with the backend's
  own runtime service account: no key file. Once per environment, a project owner of `goaway-483709` grants it the
  sending role, then sets the repo variable `FCM_PROJECT` to `goaway-483709` and redeploys:

  ```bash
  gcloud projects add-iam-policy-binding goaway-483709     --member "serviceAccount:goawaygo-backend@goawaygo-staging.iam.gserviceaccount.com"     --role roles/firebasecloudmessaging.admin --condition None
  ```

  Without `FCM_PROJECT`, nothing is pushed (the bell still fills). A failed push is logged and dropped; tokens
  Firebase reports as gone are deleted.

- **Trip reminders** ("in 3 days", "tomorrow", in India time) are sent by `POST /api/notifications/reminders`, which
  the **Daily jobs** workflow calls at 09:00 IST (Cloud Run gives no CPU between requests, so no timer inside the
  backend). The call is public and only sends what is due, once; it also deletes notifications older than 90 days.
  Run it by hand: Actions → Daily jobs → Run workflow.
- **Announcements**: admins send them to everyone from the website, or `POST /api/admin/announcements`
  `{"title": "...", "text": "..."}`.

### Trip ideas (4.7)

- Home's **Ideas for your next trip** are made by the **Daily jobs** workflow's third step
  (`POST /api/discover/refresh-ideas`, public): once the list is 20 hours old it asks the AI for a new one, checks each
  place on Wikipedia, then translates it into every language, side by side. The list and its translations are kept in
  the `idea_pools` table, so a new backend instance serves them at once and nobody on Home waits for the AI. Called
  again with nothing due, it does nothing. Run it by hand: Actions → Daily jobs → Run workflow.

### Deleting accounts (4.6)

- **Delete account** (`POST /api/auth/delete-account`, after the password or Google) signs the account out
  everywhere, stops its pushes and hides it from others at once, and emails the date it will be deleted. Signing in
  again within 30 days keeps it.
- After 30 days, the **Daily jobs** workflow's second step (`POST /api/auth/purge-deleted-accounts`, public, only
  deletes what is due) deletes the account's photo files from R2, then the account itself, which takes everything it
  owns (every table cascades), and emails a last confirmation. If R2 can't be reached, that account waits for the
  next day, so no file is left behind.
- A request by email (the website's Delete my data page), once it is confirmed to come from the account's address:
  in Neon, set `deletion_requested_at = now()` on that user and delete their `refresh_tokens` rows; the daily job
  deletes the account 30 days later.

## Releasing

What happens after a merge to `develop`, and how to publish a new Android build. Applies to all three repos
(`goawaygo_backend`, `goawaygo_web_frontend`, `goawaygo_app_frontend`). Setup details: each repo's `docs/`.

### After every merge to `develop`

Every merge whose CI passes becomes a **version** (MVP 2, 5.2): a `vMAJOR.MINOR.PATCH` tag and a GitHub Release
(a pre-release until it reaches production) listing the merged PRs. The PR title decides the number: `feat:` → minor,
`fix:` and everything else → patch, `feat!:` or `BREAKING CHANGE` → major (`.github/scripts/next-version.sh`). Each
repo has its own versions; all three started at `v1.5.0` (MVP 1).

| Part        | You do                                | Happens automatically                                                                                                                                 | Check                                                     |
| ----------- | ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| Backend     | Nothing                               | CI tests → version → image built once (tagged with the version) → deployed to **dev** (`api-dev.goawaygo.com`) → smoke test; Flyway migrates (~6 min) | GitHub → goawaygo_backend → Actions → **Release** ✅      |
| Website     | Nothing                               | CI → version → website built once, attached to the release, deployed to **dev** (`dev.goawaygo.com`) (**Release**)                                    | GitHub → goawaygo_web_frontend → Actions → **Release** ✅ |
| Android app | Release it (below) when worth testing | CI → version (**Release**). The APK is signed with the upload key that exists only on the release PC                                                  | `https://goawaygo.com/download` shows the new version     |

The app doesn't need a release after every merge: release at the end of a feature or when testers should try
something.

### Release a new Android build (~10 min)

Git Bash, one command per line:

```bash
cd /c/Users/priyesh/OneDrive/Desktop/Dev/Goaway/app_frontend
git switch develop
git pull
export PATH="$PATH:/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin"
bash scripts/release-android.sh 1.0.0 <next-build> "What changed in this version" --upload
```

- `<next-build>`: the last build number + 1. **It must always go up**, or phones refuse the update.
- Ends with `Live: https://goawaygo.com/download`. If it asks for Cloudflare, run
  `npx --yes wrangler@4.145.0 login` once and repeat.
- It refuses to run without the upload key (`android/key.properties`), so nothing debug-signed is ever published.
- Push notifications need the Firebase key on this PC: run `bash scripts/firebase-key.sh` once (gcloud signed in).
  Without it the script warns and that build has no push.
- Fails with `cannot find symbol ... Plugin` in `GeneratedPluginRegistrant.java` (usually after a dependency
  update): an old partial build of that plugin is left on this PC. Run `flutter clean` and run the command again.

Then send it to the beta testers:

1. Firebase console → project **goaway-483709** → **App Distribution**.
2. Drag in `app_frontend/build/release/goawaygo-<version>-<build>-universal.apk`, paste the same notes.
3. Testers → group **public-beta** → **Distribute**.

Check `https://goawaygo.com/download`, then install it on a phone over the previous build.

#### Version numbers

| Change          | Example                    |
| --------------- | -------------------------- |
| Every release   | build number +1, always    |
| Fixes           | keep the version (`1.0.0`) |
| New feature set | `1.1.0`                    |
| Big milestone   | `2.0.0`                    |

### Occasionally

| Situation                         | What to do                                                                                                                                                                        |
| --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| New backend secret (API key etc.) | Stored with a hidden-input script into Google Secret Manager (`backend/scripts/gcp-secrets.sh`)                                                                                   |
| New website setting               | A change to `src/lib/env.ts` (one build serves every environment), merged like any other change                                                                                   |
| Database change                   | Nothing: Flyway migrates on deploy. Keep migrations additive so a rollback still works.                                                                                           |
| Broken in test or prod            | Roll back with **Controller** → Run workflow (above). Instant backend switch: `gcloud run services update-traffic <service> --region asia-southeast1 --to-revisions REVISION=100` |
| Website broken in prod (urgent)   | Cloudflare → goawaygo-web-prod → Deployments → earlier deployment → **Rollback**; then the Controller as above                                                                    |
| Bad app build                     | Release a fixed build with a higher build number; delete the bad files from R2 and purge their URLs in Cloudflare cache                                                           |

### Moving a version up: test, then prod (`controller.yaml`, MVP 2, 5.4)

dev gets every version automatically. test and prod get a version only through **Controller**
(`.github/workflows/controller.yaml` in each repo), with your approval twice: once for the merge, once for the deploy.

1. **To test:** a PR from `develop` into `test` (opened by the bot). You approve it; it's merged with a merge commit.
2. Controller starts on `test`. It picks the version whose files are exactly what `test` now holds, checks that the
   version passed dev (its GitHub Release shows **✅** for the dev deployment, **AT dev** and **PT dev**), then waits
   for you: Actions → **Controller** → the
   run → **Review deployments** → tick **test** → **Approve and deploy**.
3. The backend and website deploy that version's image and build to test (`api-test.goawaygo.com`,
   `test.goawaygo.com`) and check them. The app has nothing to deploy: the version is recorded as promoted.
4. The automation (AT) and performance (PT) tests run on test right after the deploy and write their result into the
   release; then test it yourself on test.
5. **To prod:** a PR from `test` into `main`, approved and merged the same way; Controller checks the version passed
   test (deployment, AT and PT), waits for your approval (**prod**), deploys, and makes the version the **latest full release** (no longer a
   pre-release). For the app, build the APK from that version (below).

Each step is written into the version's GitHub Release under **Deployments**, e.g.
`- **test**: ✅ deployed 2026-11-12 09:30 UTC · run`, so every release shows where it has run. If a version isn't
ready (Release on `develop` still running, or it failed below), Controller stops with the reason; fix it and re-run.

### Automated and performance tests (AT, PT; MVP 2, 5.5 and 5.6)

The tests live in their own repos, with the same branches and rules: **goawaygo_automation_tests** (API and website
with Playwright, the Android app with Maestro on an emulator) and **goawaygo_performance_tests** (API and website with
k6, the app's start-up time and scrolling). Each environment runs the tests from its own branch (dev: `develop`,
test: `test`, prod: `main`), so a test change is promoted like code.

| When                          | What runs                                                                    | Evidence                                    |
| ----------------------------- | ---------------------------------------------------------------------------- | ------------------------------------------- |
| A pull request into `develop` | **Tests before merge**: the smoke tests against dev (app: built from the PR) | the report on the PR (not a required check) |
| After every deploy to dev     | **Release → Tests on dev**: all AT, and PT with limits that fail the run     | `AT dev` / `PT dev` ✅ or ❌ in the release |
| After a deploy to test        | **Controller → Tests on test**: the same                                     | `AT test` / `PT test` in the release        |
| After a deploy to prod        | **Controller → Tests on prod**: read-only smoke tests, never PT              | `AT prod` in the release                    |

A version goes to test only with ✅ for AT and PT in dev, and to prod only with ✅ for both in test. Every run keeps
its reports for 90 days: the run's page → **Artifacts** (Playwright HTML report, k6 HTML report, app screenshots and
timings). In dev and test the tests use the backend's fake outside services (no AI or partner quota is used) and skip
its rate limits; prod has neither. A failed run: open the report, fix the code or the test, and re-run (Actions →
the run → **Re-run failed jobs**); the release then shows the new result.

**Roll back or deploy again:** Actions → **Controller** → **Run workflow** → "Use workflow from" **main** (prod) or
**test** (test) → version, e.g. `v1.7.0` (empty = the branch's own version) → approve. Only a version that is merged
into that branch and passed the environment below can be deployed. Keep database migrations additive so an older
version still runs.

## How we work

These rules apply to all GoAwayGo repositories:
[app](https://github.com/goawaygoofficial/goawaygo_app_frontend) ·
[web](https://github.com/goawaygoofficial/goawaygo_web_frontend) ·
[backend](https://github.com/goawaygoofficial/goawaygo_backend).

### First-time setup

```bash
git clone <repo-url>
cd <repo>
git config core.hooksPath .githooks   # enable the shared git hooks (once per clone)
```

The hooks:

- **pre-commit** scans staged changes for secrets with [Gitleaks](https://github.com/gitleaks/gitleaks).
  Install it once: `winget install Gitleaks.Gitleaks` (Windows) or `brew install gitleaks` (macOS).
  Without it, the hook skips with a warning, and CI still scans.
- **pre-push** blocks pushing directly to `develop`, `test` or `main`, and branch names other than `feature/...`
  and `fix/...`.

Never bypass hooks with `--no-verify`.

### Branches

Every change takes the same path: `feature/...` or `fix/...` → `develop` → `test` → `main`.

| Branch              | Is for            | Deploys to        | Merges into                                 |
| ------------------- | ----------------- | ----------------- | ------------------------------------------- |
| `feature/<name>`    | A new feature     | Nowhere (CI only) | `develop`: squash merge, no approval needed |
| `fix/<name>`        | A bug fix         | Nowhere (CI only) | `develop`: squash merge, no approval needed |
| `develop` (default) | Development       | Dev environment   | `test`: merge commit, the owner approves    |
| `test`              | Testing a version | Test environment  | `main`: merge commit, the owner approves    |
| `main`              | Customers         | Production        | —                                           |

- Branch from the latest `develop`: `git switch develop && git pull && git switch -c feature/short-name`.
  Names: lowercase letters, digits, `.`, `_` and `-` after the prefix.
- GitHub rejects new branches with any other name (Dependabot's `dependabot/...` is the exception), direct pushes
  to `develop`, `test` and `main`, force pushes and deleting them.
- Keep branches short-lived (days, not weeks) and focused on one thing.

### Commits

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add Google sign-in button
fix: stop crash when profile picture is missing
docs: explain how to run the backend locally
chore(deps): bump http to 1.6.0
```

Types: `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `perf`, `ci`, `build`.
The squash-merged PR title sets the next version: `feat` → minor, everything else → patch, `!` after the type
(`feat!:`) or a `BREAKING CHANGE:` line → major.
Write the subject in the imperative ("add", not "added"), lower case, no trailing period.

### Pull requests

1. Run this repo's checks locally (see **This repository** below). They must pass.
2. Push your branch and open a PR into `develop`. Fill in the template.
3. The required checks must be green ✅: **CI**, **Secret scan**, **Dependency scan** and **Branch guard**
   (the PR is on an allowed path).
4. **Squash merge** into `develop`. The PR title becomes the commit message, so it must follow Conventional Commits.
5. To move changes up: a PR **`develop` → `test`**, then **`test` → `main`**. Each needs the owner's approval
   (`goawaygoofficial`, the code owner) and is merged with a **merge commit**, never squash, so the branches keep
   the same history.

Rules:

- **Never merge a PR with a failing (❌) or pending check.**
- Every bug fix or feature includes tests, or the PR explains why not.
- No secrets in code. Config goes in `.env` / environment variables, with placeholders in `.env.example`.
- UI changes include before/after screenshots.

These rules are enforced by GitHub repository rulesets (the repos are public, so they are free) and the
**Branch guard** workflow.

### Bug fixes

A bug fix is a `fix/<name>` branch and follows the same path as everything else: `develop` → `test` → `main`.
There are no hotfix branches.

### Dependencies

Dependabot opens update PRs into `develop` every Monday. Minor and patch updates are grouped into one PR.
Review them like any other PR: CI must pass, and read the changelog for major updates.

### Security

- Never commit `.env`, keys, keystores, service-account files or passwords. `.gitignore` covers the usual ones.
- If a secret is committed, even on a branch, **rotate it immediately**. Deleting the commit is not enough.
  The repos are public: anything pushed can be read and copied at once.
- Report security issues privately to the repo owner, not in a public issue.

### This repository: Backend (Spring Boot)

Requires **JDK 17+** and **Docker Desktop**.

```bash
cp .env.example .env          # then fill in the three passwords (see the file)
docker compose up -d          # local PostgreSQL 17 on 127.0.0.1:5433
./mvnw spring-boot:run        # Windows PowerShell: .mvnw.cmd spring-boot:run
```

Check to run before every PR (the same one CI runs):

```bash
./mvnw verify                 # build + all tests (tests start their own PostgreSQL container)
```

Database rules:

- Schema changes are **Flyway migrations** in `src/main/resources/db/migration` (`V<n>__<description>.sql`).
  Never edit a migration after it has been merged; add a new one.
- The backend connects as `goawaygo_app` (data only). Only Flyway, as `goawaygo_owner`, changes the schema.
- Secrets (database passwords) go in `.env` locally and in environment variables when deployed, never in
  `application.properties`.

### Pull request description

Every pull request description follows this outline:

```markdown
## What

<!-- What does this PR change? Keep it short. -->

## Why

<!-- The problem it solves or the feature it adds. Link the issue if there is one: Closes #123 -->

## How tested

<!-- Commands you ran, screens you checked, devices/browsers. -->

- [ ] Checks pass locally (see **How we work** (README) for this repo's command)
- [ ] Added or updated tests for the change (or explained below why not)

## Screenshots

<!-- For UI changes: before / after. Delete this section if not relevant. -->

## Checklist

- [ ] PR targets `develop` (or `main` only for a release / hotfix)
- [ ] Title follows Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`, `refactor:`, `test:`)
- [ ] No secrets, keys or `.env` files committed
- [ ] `README.md`, `LINKS.md`, `RELEASES.md` and `.env.example` updated if behaviour, config or links changed
```

## Conventions and decisions

GoAwayGo is three product repos (plus the automation and performance test repos), cloned side by side in one folder (`Dev/Goaway` on the owner's PC):

| Folder          | Stack                                                                                                                        | GitHub repo (public)                     |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| `app_frontend/` | Flutter 3.47 (Dart ^3.13), provider, http, google_sign_in, flutter_secure_storage, Sentry, PostHog                           | `goawaygoofficial/goawaygo_app_frontend` |
| `web_frontend/` | React 19, TypeScript (strict), Vite 8, react-router-dom 7, CSS Modules — web version of the app, mirrors it screen by screen | `goawaygoofficial/goawaygo_web_frontend` |
| `backend/`      | Spring Boot 4.1, Java 17, Maven (`mvnw`), PostgreSQL 17 (Docker Compose locally, Testcontainers in tests), Flyway, Sentry    | `goawaygoofficial/goawaygo_backend`      |

The parent folder is not a git repo: run git and gh inside each repo folder.

### GitHub

- Account: `goawaygoofficial` (not the global git identity on this PC — don't use that here).
- Each repo has local git config `user.name=goawaygoofficial` and GitHub's private no-reply `user.email` (the repos are public, decided 2026-11-11: the team email stays out of commits).
- `gh` CLI is logged in as `goawaygoofficial` with ADMIN on all repos.
- Default branch: `develop`.

### Branching (all repos)

Path for every change (MVP 2, decided 2026-11-11): `feature/<name>` or `fix/<name>` → `develop` → `test` → `main`.

| Branch               | Environment | Rules                                                                                        |
| -------------------- | ----------- | -------------------------------------------------------------------------------------------- |
| `feature/*`, `fix/*` | CI only     | Branch from latest `develop`; PR into `develop`; no other branch names (GitHub rejects them) |
| `develop` (default)  | Dev         | Squash-merge PRs from `feature/*`/`fix/*`/`dependabot/*`; no approval needed                 |
| `test`               | Test        | Only a PR from `develop`, approved by `goawaygoofficial`, merged with a **merge commit**     |
| `main`               | Production  | Only a PR from `test`, approved by `goawaygoofficial`, merged with a **merge commit**        |

- PRs are opened and merged by the bot account `goawaygo-bot` (`gh` is signed in as it; these repos push through
  `gh`). The user approves PRs into `test` and `main` as `goawaygoofficial` (CODEOWNERS); a PR author can't approve
  their own PR.
- No Claude attribution: no `Co-Authored-By: Claude` trailer, no "Generated with Claude Code" line, and always an
  explicit `commit_message` when squash-merging (otherwise GitHub copies old trailers into the commit).
- Merge via the API, `develop`:
  `gh api -X PUT repos/goawaygoofficial/<repo>/pulls/<n>/merge -f merge_method=squash -f sha=<head> -f commit_title="<PR title> (#<n>)" -f commit_message="<summary>"`;
  `test` and `main`: the same with `merge_method=merge`.
- A merge into `test` or `main` starts **Controller** (`.github/workflows/controller.yaml`, 5.4): it deploys the
  version whose files match the branch to test or prod, only if that version passed the environment below (its
  GitHub Release "Deployments" section shows ✅), after the owner approves the deploy. Rollback: run Controller by
  hand on `test` or `main` with a version (**Releasing** (README)).
- Tests as evidence (5.5, 5.6): `tests.yml` in each product repo runs the automation tests (repo
  `goawaygo_automation_tests`: Playwright API + website, Maestro app) and performance tests (`goawaygo_performance_tests`:
  k6 API + website, app start-up and scrolling) from the branch matching the environment, after every deploy (Release,
  Controller) and as smoke tests on pull requests (`tests-before.yml`). Results go into the release ("AT dev ✅",
  "PT dev ✅"); Controller needs both from the environment below. dev and test answer test runs (header
  `X-GoAwayGo-Test`, secret `TEST_FAKES_TOKEN`) with fake outside services and no rate limits; prod never.
- Enforced by GitHub repository rulesets (free on public repos): branch names, no direct or force pushes, no
  deleting `develop`/`test`/`main`, required checks, the owner's approval into `test` and `main`. The
  **Branch guard** check fails a PR on any other path; the `pre-push` hook gives the same answers locally.

### Workflow rules

- Per task: branch → code + tests → run checks locally → commit → push → open PR to `develop` → stop for the user's review.
- Show the changes and get the user's OK before any PR merge, release, or deploy.
- Never merge with failing or pending checks (`gh pr checks <n>`), even if asked — fix them first.
- Never bypass git hooks (`--no-verify`). Hooks live in `.githooks/`; enable per clone with `git config core.hooksPath .githooks`.
- **Conventions and decisions** (README) exists in this folder and as a copy in each repo — keep all copies in sync.

### CI (GitHub Actions, every repo)

- `CI` — app: dart format check, `flutter analyze`, `flutter test`, debug APK build · web: `npm run check` · backend: `./mvnw verify`
- `Secret scan` — Gitleaks over full history
- `Branch guard` — on every PR: only allowed merge paths (required check)
- Dependabot: weekly grouped update PRs into `develop` (deps + GitHub Actions)
- Free plan = 2,000 Actions minutes/month; avoid needless re-runs.

### Secrets — never commit

- App frontend: `.env` (bundled as a Flutter asset — public config only: `BASE_URL`, `WEBSITE_URL`, `GOOGLE_WEB_CLIENT_ID`, `GOOGLE_IOS_CLIENT_ID`, `SENTRY_DSN`, `POSTHOG_KEY`; keep `.env.example` in sync), `android/app/google-services.json` (if Firebase is ever added), `android/local.properties`, keystores, `key.properties`.
- Web frontend: `.env` / `.env.*` (`VITE_*` values end up in the JS bundle — public config only; keep `.env.example` in sync).
- Backend: `backend/.env` (gitignored; template `.env.example`) holds local DB passwords and is read by both Docker Compose and Spring Boot (`spring.config.import`). Deployed environments use env vars. Never put secrets in `application.properties`. Never print `.env` values in chat.

### Commands

- App frontend: `flutter pub get`, `flutter analyze`, `flutter test`, `flutter run`
- Web frontend (Node 22.12+, `.nvmrc` = 24): `npm install`, `npm run dev`, `npm run check` (typecheck + lint + format check + build — run before every PR)
- Backend (JDK 17+, Docker Desktop): `docker compose up -d` (PostgreSQL 17 on 127.0.0.1:5433), `./mvnw spring-boot:run`, `./mvnw verify` (tests start their own PostgreSQL via Testcontainers)
- App CI pins Flutter 3.47.7 (matches local); update `.github/workflows/ci.yml` when upgrading.

### Database (backend)

- Roles: `postgres` (container superuser, setup only) · `goawaygo_owner` (owns schema; Flyway only) · `goawaygo_app` (runtime; SELECT/INSERT/UPDATE/DELETE, no DDL). Created by `docker/postgres/init/01-create-roles.sh`, reused by tests; `DatabaseSetupTests` verifies it.
- Schema changes only via new Flyway migrations `src/main/resources/db/migration/V<n>__<desc>.sql`; never edit a merged migration.
- Backend runs in UTC (`BackendApplication.main` + surefire `-Duser.timezone=UTC`): Windows reports the legacy zone `Asia/Calcutta`, which PostgreSQL 17 (Debian 13 image) rejects.
- Keep the `postgres:` image tag in `compose.yaml` and `PostgresIntegrationTest` in sync.

### Authentication (decided: short JWT + rotating refresh token)

- Access token: ES256 JWT, **20 min**, `Authorization: Bearer`; not stored. Keys: `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` (base64 DER) in `backend/.env`; each environment has its own pair; never print or commit them.
- Refresh token: random, **30 days**, stored only as SHA-256 in `refresh_tokens`; rotated on every use; reuse of a rotated token revokes the whole family.
- Mobile sends `X-Client-Type: mobile` → refresh token in JSON body (store in Keychain/Keystore). Web → refresh token only in `HttpOnly; Secure; SameSite=Strict` cookie `goawaygo_refresh` on `/api/auth`; keep access token in memory, never `localStorage`.
- Profile: `GET /api/users/me` (own profile only). There is no `GET /api/auth/users/{id}`: the old frontend code that calls it must be replaced.
- Errors: always `{status, error, message[, fieldErrors]}`; clients branch on `error`.
- Never trust identity from the client; derive the user from the access token (`jwt.getSubject()`).
- Password reset / email verification: single-use emailed links (`user_action_tokens`, SHA-256 hashes; reset 30 min, verify 24 h) opening the website (`/reset-password`, `/verify-email`). Forgot-password must not reveal whether an account exists (same 202 body; email sent after commit in the background). Never accept `{email, newPassword}`.
- Email: SMTP only (`EmailSender`/`EmailOutbox`). Local: Mailpit from `docker compose` (inbox http://localhost:8025). Deployed: Resend over SMTP (`MAIL_*` env vars; the API key is a secret). Never log email bodies (they contain one-time links).
- Rate limits (Bucket4j, in-memory per instance): per IP on public `/api/auth/*`, per email on signin/forgot-password; 429 + `Retry-After`. Tests disable it in `PostgresIntegrationTest`.

### Google Sign-In

- App ID (Android `applicationId`/`namespace`, iOS/macOS bundle ID): **`com.goawaygo.app`**. Permanent once on Google Play.
- App minimum OS: **Android 8.0** (API 26, `minSdk = 26` in `android/app/build.gradle.kts`), **iOS 15.0**, macOS 12.0, required by `sentry_flutter` 10. Don't lower them.
- Google Cloud project `goaway-483709` (number `1018541563002`), consent screen in **Testing** (only listed test users can sign in). OAuth clients (IDs are public; never handle client secrets):
  - Web `1018541563002-sft2jld5f3n6lnenvt0pk8hknk7hfq9n.apps.googleusercontent.com`: website, and the ID-token audience (`serverClientId`) for the mobile app. Origins: `http://localhost:5173`, `http://localhost`.
  - Android `1018541563002-s8eo8sn5e7dt9kaav6rm0mhgea6tgir9.apps.googleusercontent.com`: package + the user's debug SHA-1 `79:E2:F5:3A:AD:5E:E7:84:08:30:CE:07:1C:9A:F0:EC:FB:90:2F:C8`. Release / Play App Signing SHA-1 needs its own Android client later.
  - iOS `1018541563002-ss0dur7e8d4l9c2lb2o89edgouqrd2fv.apps.googleusercontent.com`
- Backend must verify Google ID tokens (signature via Google JWKs, `iss`, `exp`, `aud` ∈ configured client IDs, `email_verified`) and never trust user details sent alongside them.
- Mobile sends the ID token to `/api/auth/google-signin`. The website uses its own styled button + Google code client (popup) and sends the one-time code to `/api/auth/google-signin/code`; the backend exchanges it with `GOOGLE_CLIENT_SECRET` (backend `.env` only, entered by the user; never ask for it in chat, never print it).

### Discover, Wanderbook, Book, the trip planner, notifications and Me (4.1-4.6)

- **How we build:** MVP rounds. MVP 1 builds every Phase 4 sub-phase; fixes, changes and new ideas go into later MVPs (see **Roadmap** (README)).
- Outside services go through the backend's API toolkit (`com.goawaygo.backend.external`) with daily limits below free tiers: Gemini (destination cards, trip ideas), Geoapify (places, photo location → place name), Wikimedia and Unsplash (photos), OpenAI moderation (Wanderbook), Travelpayouts (Book: Aviasales prices, affiliate links). Keys: Secret Manager `goawaygo-api-<name>` + repo variable `EXTERNAL_API_PROVIDERS`. Never store Discover's AI answers or spot photos (asked fresh on every page open); a trip plan is the user's own content once saved (`trip_plans`).
- Wanderbook photos: originals (never resized), only location data removed by the backend; exact coordinates never stored. Stored in R2 `goawaygo-media` (`media.goawaygo.com`), key via `scripts/gcp-secrets.sh <project> r2` + repo variable `WANDERBOOK_PHOTOS=true`. Uploads stop at 9.5 GB.
- Moderation fails closed: if OpenAI can't check, nothing is posted. Reports email `team@goawaygo.com`; admins (`users.is_admin`, set by hand in Neon) review on the website's `/admin/reports`.
- Viewing Wanderbook is public (rate limited per IP); profiles/posts use random 12-character public ids, never user ids.
- Trip planner: Gemini (Lite models first) writes a day-by-day plan with rough costs (whole group, flights never included); signed-in users only, saved in My Trips, one day can be rewritten, a plan can be shared read-only by link after an OpenAI moderation check.
- Notifications: saved for the bell (90 days) on the app and website; pushed to Android through Firebase Cloud Messaging with the backend's own service account (no key); a switch per type; trip reminders run from a daily GitHub Actions call. The app's Firebase IDs are public values in `env/staging.env` (never commit `google-services.json`).
- Languages (4.7): en, hi, es, fr, de, ar (RTL), ja. Backend text the user sees goes through `T.t(lang, "English sentence {0}", args)` with translations in `src/main/resources/i18n/<code>.json` (the English sentence is the key; `LanguageTests` checks every file has the same keys and placeholders). New user-facing backend text needs all six translations. Requests carry `Accept-Language`; `users.language` is for pushes and emails. Legal pages stay English.
- Me: the account name and photo are also the Wanderbook name and photo. Changing the password or email and deleting the account first confirm it's you (password, or Google without one). Deleting hides the account for 30 days (signing in keeps it), then the daily GitHub Actions job deletes its R2 files and the account (every table cascades); queries that show people to others must skip `users.deletion_requested_at IS NOT NULL`. Phone sign-in waits for MVP 2 (SMS costs money).
- Book: affiliate model, travellers book and pay on the partner's site. Flights show Aviasales' recent prices (labelled "may change"); stays and cars are partner links. Every partner page becomes our tracked link via Travelpayouts (`goawaygo.book.*`); the app only shows brands that allow apps. Never load partner scripts (e.g. Travelpayouts Drive) on our site.

### Deployment

Domain `goawaygo.com` (Cloudflare Registrar + DNS). Website and API must stay on the same registrable domain (the refresh cookie is `SameSite=Strict`).

- Pre-launch (alpha/beta, all free): backend on Google Cloud Run `asia-southeast1` (prod: project `goawaygo-prod`, service `goawaygo-backend`, `api.goawaygo.com`; dev and test: project `goawaygo-staging`); database Neon PostgreSQL 17 (AWS Singapore); website on Cloudflare Pages (prod `goawaygo.com` since 2026-11-13 with `www` redirecting to it, dev `dev.goawaygo.com`, test `test.goawaygo.com`; no other addresses); email Resend; Android testers via Firebase App Distribution.
- Naming (MVP 2): **staging** = non-production (dev, test), **prod** = production. Neon projects `goawaygo-staging` (`goawaygo_dev`, `goawaygo_test`) and `goawaygo-prod` (`goawaygo`); Google Cloud projects `goawaygo-staging` (dev, test, the shared image registry) and `goawaygo-prod` (production, since 2026-11-12). A shared key (Google, Resend, R2, outside APIs) is changed in both projects.
- At launch (Phase 5): database moves to Google Cloud SQL; Play Console then. Company not registered yet.
- Backend: every version deploys to dev automatically after CI passes on `develop` (`.github/workflows/release.yml` → `deploy.yml`, keyless Workload Identity Federation); test and prod get the same image through `controller.yaml` (5.4). Environments: the backend README (**Deployment**). Secrets live only in Google Secret Manager; setup scripts and steps in the backend README (**Deployment**).
- Cloud Run: min 0 instances (cold start ~7–10 s after idle), max 1 (in-memory rate limits), request-based billing (CPU only during requests; emails sent inside the request). Health: `GET /actuator/health` (no DB check, so Neon can sleep).
- Google free trial ends 90 days after sign-up: upgrade billing before then or the project stops.
- After a merge: backend and website deploy themselves; Android builds are released by hand with `app_frontend/scripts/release-android.sh` (upload key on the release PC only). Step by step: **Releasing** (README) (copy in every repo). What's next: **Roadmap** (README) (copy in every repo; tick items as they ship).
- Shared docs, identical in the workspace root and every repo (edit the root copy, then copy it into the three repos with their `<!-- Copy of ... -->` header): **Conventions and decisions** (README), **Releasing** (README), **Roadmap** (README), **Resources and outside services** (README) (what each service is for) `LINKS.md` (dashboards, routine, onboarding) and **History** (README) (the goawaygo.com launch checklist, 5.7).

## Resources and outside services

Every service, library and tool GoAwayGo uses, what it does for us, and which part uses it: **B** = backend,
**W** = website, **A** = Android app. All on free tiers until launch. Links: `LINKS.md`.

### At a glance

```
 Users ──► goawaygo.com (W, Cloudflare Pages) ──────────┐
       └─► GoAwayGo app (A, APK from downloads.goawaygo.com / Firebase) ──┤
                                                          ▼
                              api.goawaygo.com (B, Google Cloud Run) ──► Neon Postgres (database)
                                     │          │            │
                                Resend (email)  Secret Manager   outside APIs (via the API toolkit):
                                                                 Gemini, Geoapify, Wikimedia, Unsplash, OpenAI
                                     │
                                     └──► Cloudflare R2 goawaygo-media ──► media.goawaygo.com (Wanderbook photos)

 Watching it: Sentry (errors) · Cloud Logging (logs) · Better Stack (uptime + status page)
              Cloudflare Web Analytics (traffic) · PostHog (product analytics, with consent)
 Guarding it: GitHub Actions CI · Gitleaks (secrets) · OSV-Scanner (vulnerable dependencies) · Dependabot
```

### 1. Running the product

| Resource                                                    | What it does for us                                                                                                                | Used by              | Free limit / cost                                                                   | Set up in                                                            |
| ----------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | -------------------- | ----------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| **Google Cloud Run** (`goawaygo-backend`, Singapore)        | Runs the backend container; serves `api.goawaygo.com`. Scales to 0 when idle, max 1 instance; billed only while handling requests. | B                    | 180k vCPU-s, 360k GiB-s, 2M requests/month free; $300 trial credit until 2027-01-14 | `backend/.github/workflows/deploy.yml`, **Deployment** (README)      |
| **Neon Postgres 17** (AWS Singapore)                        | The database: users, sessions, email links, API cache, searches, saved places, Wanderbook. Sleeps when idle.                       | B                    | 0.5–1 GB storage, 100 compute-hours/month                                           | `backend/scripts/setup-hosted-db.sh`; tables in `db/migration`       |
| **Flyway**                                                  | Creates and updates database tables on every deploy (V1–V7), as the owner role; the app uses a limited role.                       | B                    | open source                                                                         | `backend/src/main/resources/db/migration`                            |
| **Cloudflare Pages** (`goawaygo-web-dev`, `-test`, `-prod`) | Hosts the website: one project per environment, uploaded by GitHub Actions (one build per version).                                | W                    | unlimited static requests, 500 builds/month                                         | Cloudflare dashboard; the website README (**Deployment**)            |
| **Cloudflare R2** (`goawaygo-downloads`)                    | Stores the Android APKs and `latest.json` behind `downloads.goawaygo.com`.                                                         | A, W (download page) | 10 GB, no download fees                                                             | `app_frontend/scripts/release-android.sh`                            |
| **Firebase App Distribution** (project `goaway-483709`)     | Sends beta builds to testers; public sign-up link.                                                                                 | A                    | free                                                                                | the app README (**Android releases**)                                |
| **Cloudflare DNS + Registrar** (`goawaygo.com`)             | The domain and all its records (web, api, downloads, status, email).                                                               | B, W, A              | at-cost domain renewal (yearly)                                                     | Cloudflare dashboard                                                 |
| **Resend**                                                  | Sends account emails (verify email, reset password) as `no-reply@goawaygo.com`.                                                    | B                    | 3,000 emails/month, 100/day                                                         | Secret `goawaygo-resend-api-key`, repo variable `MAIL_FROM`          |
| **Google Sign-In** (OAuth clients in `goaway-483709`)       | "Continue with Google" on the website and in the app.                                                                              | B, W, A              | free                                                                                | Repo variables + `goawaygo-google-client-secret`; app `env/prod.env` |

### 2. Keeping it secure

| Resource                                             | What it does for us                                                                                                                                      | Used by     | Set up in                                                   |
| ---------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------- | ----------------------------------------------------------- |
| **Google Secret Manager**                            | Holds every secret (DB passwords, JWT keys, Google client secret, Resend key, future API keys); the backend reads them at start. GitHub never sees them. | B           | `backend/scripts/gcp-secrets.sh`                            |
| **Workload Identity Federation**                     | GitHub Actions deploys to Google Cloud without any stored key.                                                                                           | B (deploys) | `backend/scripts/gcp-bootstrap.sh`                          |
| **Android upload key** (`~/.goawaygo`, this PC only) | Signs every APK so phones accept updates; its SHA-1 lets Google Sign-In trust the app. Back it up; never share it.                                       | A           | `app_frontend/scripts/create-upload-key.sh`                 |
| **Gitleaks** (pre-commit hook + CI)                  | Blocks commits and PRs that contain passwords or keys. `.gitleaks.toml` allows only the public PostHog key.                                              | B, W, A     | `.githooks/pre-commit`, `.github/workflows/secret-scan.yml` |
| **OSV-Scanner** (CI, also every Monday)              | Checks every dependency against known vulnerabilities (osv.dev); a finding fails the check.                                                              | B, W, A     | `.github/workflows/dependency-scan.yml`                     |
| **Dependabot**                                       | Weekly PRs that update libraries (grouped minor/patch; majors separate).                                                                                 | B, W, A     | `.github/dependabot.yml`                                    |
| **Rate limits** (Bucket4j, in memory)                | Limits repeated sign-in, sign-up and refresh attempts per IP and per email.                                                                              | B           | `goawaygo.rate-limit.*` in `application.properties`         |
| **Security headers**                                 | Stops the website being framed by other sites, limits referrers, turns off unused browser features.                                                      | W           | `web_frontend/public/_headers`                              |

### 3. Knowing when something breaks (Foundations 4.0a–4.0b)

| Resource                                                                                 | What it does for us                                                                                                                                 | Used by | Free limit                 | Set up in                                                                                                            |
| ---------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | -------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| **Sentry** (EU, org `goawaygo`; projects goawaygo-backend / goawaygo-web / goawaygo-app) | Catches crashes and errors with the release and environment; emails us; no personal data (scrubbers on, IPs not stored, tokens cut from URLs).      | B, W, A | 5k errors/month            | `SENTRY_DSN` (backend var), `web_frontend/src/lib/monitoring.ts`, `app_frontend/lib/core/monitoring/monitoring.dart` |
| **Google Cloud Logging + Error Reporting**                                               | Backend logs as structured JSON (severity, request ID, trace), kept 30 days; groups backend exceptions.                                             | B       | 50 GiB/month               | `logging/CloudLoggingFormatter.java`, `RequestIdFilter.java`                                                         |
| **Request IDs** (`X-Request-Id`)                                                         | One ID per API request in the response, every log line and Sentry — to follow one request end to end.                                               | B       | —                          | `logging/RequestIdFilter.java`                                                                                       |
| **Health endpoint** `/actuator/health`                                                   | "Is the backend running?" for uptime checks; never touches the database (so Neon can sleep).                                                        | B       | —                          | `application.properties` (management.*)                                                                              |
| **Better Stack**                                                                         | Checks the API and website every 3 min and `latest.json` every 30 min from 4 regions; emails on downtime; public status page `status.goawaygo.com`. | B, W, A | 10 monitors, 1 status page | Better Stack dashboard                                                                                               |

### 4. Understanding usage (Foundation 4.0d)

| Resource                               | What it does for us                                                                                                                                                                                                                                                                               | Used by | Free limit      | Set up in                                                                             |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | --------------- | ------------------------------------------------------------------------------------- |
| **Cloudflare Web Analytics**           | Page views and visitors for the website; no cookies, no consent needed.                                                                                                                                                                                                                           | W       | free            | Cloudflare Pages → Web Analytics (automatic)                                          |
| **PostHog** (EU, project 315148)       | Product analytics — only after the user taps "Allow": page views and 6 events (sign-up, sign-in, travel category, destination search, app download, beta join), anonymous. **Feature flags** are available here but switched off until we first need one (a one-line change each in web and app). | W, A    | 1M events/month | `web_frontend/src/lib/analytics.ts`, `app_frontend/lib/core/analytics/analytics.dart` |
| **Consent banner / "Privacy choices"** | Asks once (equal "No thanks" / "Allow"); the choice can be changed any time; "No thanks" deletes PostHog's ID.                                                                                                                                                                                    | W, A    | —               | `ConsentBanner.tsx`, `analytics_consent_sheet.dart`                                   |

### 5. Legal and contact (Foundation 4.0c)

| Resource                                         | What it does for us                                                                                                                                                                                  | Used by | Set up in                                             |
| ------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ----------------------------------------------------- |
| **Privacy Policy, Terms of Use, Delete my data** | International (GDPR, US state laws, India DPDP, Brazil LGPD); linked from footers, sign-up and the app. Delete-my-data is also Google Play's required deletion URL. **Lawyer review before launch.** | W, A    | `web_frontend/src/features/legal`, `src/lib/legal.ts` |
| **Cloudflare Email Routing**                     | `privacy@` and `support@goawaygo.com` forward to the team Gmail (free; receive only).                                                                                                                | all     | Cloudflare → Email Routing                            |

### 6. Calling outside services safely (Foundation 4.0e)

| Resource                                          | What it does for us                                                                                                                                                                                                   | Used by | Set up in                                |
| ------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | ---------------------------------------- |
| **API toolkit** (`com.goawaygo.backend.external`) | The one way the backend calls outside APIs (places, photos, prices, AI from 4.1): timeouts, retries for temporary failures, circuit breaker, daily/monthly limits **below free tiers**, database cache with fallback. | B       | **Deployment** (README) → "Outside APIs" |
| **Resilience4j**                                  | The retry and circuit-breaker engine inside the toolkit.                                                                                                                                                              | B       | `pom.xml`                                |
| **Usage counters** (`external_api_usage`)         | Calls per provider per day/month; refuses calls at the limit, so no surprise bills.                                                                                                                                   | B       | Flyway V4                                |
| **API cache** (`external_api_cache`)              | Reuses answers instead of spending quota; serves the last answer if a provider is down.                                                                                                                               | B       | Flyway V4                                |

### 7. Building and shipping

| Resource                                                        | What it does for us                                                                               | Used by |
| --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- | ------- |
| **GitHub** (org `goawaygoofficial`, private repos)              | Code, PRs, reviews; `develop` → dev, `test` → test, `main` → prod.                                | B, W, A |
| **GitHub Actions**                                              | CI on every PR (build, tests, lint, format), secret and dependency scans, and the backend deploy. | B, W, A |
| **Docker + Artifact Registry** (`goawaygo` repo, newest 3 kept) | Packages the backend and stores the images Cloud Run runs.                                        | B       |
| **Wrangler** (Cloudflare CLI, logged in on this PC)             | Uploads APKs and `latest.json` to R2 during an Android release.                                   | A       |
| **Testcontainers + Docker**                                     | Real Postgres in backend tests.                                                                   | B       |

### 8. Main frameworks and libraries

| Part    | Main pieces                                                                                                                                                  |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Backend | Java 17, Spring Boot 4.1 (web, security, JPA, validation, mail, actuator), Flyway, PostgreSQL driver, JWT (ES256), Bucket4j + Caffeine, Resilience4j, Sentry |
| Website | React 19, TypeScript, Vite, React Router, Sentry, PostHog (loaded only after consent), Roboto (bundled)                                                      |
| App     | Flutter 3.47, provider, http, google_sign_in, flutter_secure_storage, shared_preferences, url_launcher, flutter_dotenv, Sentry 10, PostHog                   |

### 9. Feature services (4.1 Discover, 4.2 Wanderbook, 4.3 Book, 4.4 Trip planner, 4.5 Notifications)

All called by the backend through the API toolkit (section 6), with daily limits below the free tiers. Keys live in
Secret Manager (`goawaygo-api-<name>`); the repo variable `EXTERNAL_API_PROVIDERS` lists the ones the deploy passes.

| Resource                                                            | What it does for us                                                                                                                    | Used by                  | Free limit / cost                                                           | Set up in                                                                                 |
| ------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- | ------------------------ | --------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| **Google Gemini API** (key in "Default Gemini Project", no billing) | Writes the 9 destination cards, the hourly trip ideas and trip plans (plans: Lite models first). Cards: 3 Flash → 3.1 Lite → 3.5 Lite. | B                        | free tier (3 Flash about 20 requests/day; Lite models more); paid at launch | **Discover: destination pages, photos and ideas** (backend README), `goawaygo.discover.*` |
| **Geoapify**                                                        | Place search while typing, place details, and the place name for a photo's location.                                                   | B                        | 3,000 credits/day                                                           | `goawaygo.external.providers.geoapify.*`                                                  |
| **Wikipedia, Wikidata, Wikimedia Commons**                          | Tourist-spot photos and their credits; confirms trip ideas are real places.                                                            | B (W, A load the photos) | free, no key (User-Agent required)                                          | `SpotPhotos`, `Ideas`                                                                     |
| **Unsplash**                                                        | Backup spot photos (credited, with referral links).                                                                                    | B (W, A load the photos) | demo app: 50 requests/hour                                                  | `goawaygo.external.providers.unsplash.*`                                                  |
| **OpenAI moderation** (`omni-moderation-latest`)                    | Checks every Wanderbook photo and text before it appears; nothing is posted if it can't check.                                         | B                        | free                                                                        | Secret `goawaygo-api-openai`, `ContentModeration`                                         |
| **Cloudflare R2** (`goawaygo-media`)                                | Stores Wanderbook photos (originals, location removed) behind `media.goawaygo.com`.                                                    | B (W, A show them)       | 10 GB; uploads stop at 9.5 GB                                               | Secrets `goawaygo-r2-*`, repo variable `WANDERBOOK_PHOTOS`, **Deployment** (README)       |
| **Travelpayouts** (marker 798632, project 597168)                   | Recent flight prices (Aviasales Data API) and affiliate links for flights, hotels and car rentals (links API).                         | B (W, A open the links)  | free; we earn commission on bookings                                        | Secret `goawaygo-api-travelpayouts`, `goawaygo.book.*`                                    |
| **KAYAK API** (sandbox)                                             | Live flight, hotel and car prices once production is approved (not used yet).                                                          | B (later)                | free sandbox; production by approval                                        | Secret `goawaygo-api-kayak`                                                               |
| **Firebase Cloud Messaging** (project `goaway-483709`)              | Push notifications to the Android app (Wanderbook activity, trip reminders, shared plans, announcements).                              | B (A receives)           | free                                                                        | Repo variable `FCM_PROJECT`; runtime account's FCM role (**Deployment** (README))         |
| **Admin flag** (`users.is_admin`)                                   | Who may review Wanderbook reports on `/admin/reports`; reports are also emailed to `team@goawaygo.com`.                                | B, W                     | —                                                                           | Set by hand in Neon (**Deployment** (README))                                             |

### Deadlines and limits to watch

| When            | What                                                                                                                                                                          |
| --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **~2027-01-04** | Upgrade Google Cloud billing before the free trial ends **2027-01-14** (otherwise the backend stops).                                                                         |
| Monthly         | Neon compute hours (100), Resend emails (3,000), Sentry errors (5k), PostHog events (1M).                                                                                     |
| Daily / storage | Gemini free requests, Geoapify 3,000 credits/day, Unsplash 50/hour; R2 `goawaygo-media` 10 GB (Wanderbook uploads stop at 9.5 GB).                                            |
| Before launch   | Lawyer review of legal pages; company registration; Play Console + Android developer verification (rule reaching India in 2027); production on `goawaygo.com` with Cloud SQL. |
| DPDP            | India's substantive obligations apply from **13 May 2027**.                                                                                                                   |
| Yearly          | `goawaygo.com` renewal (Cloudflare Registrar).                                                                                                                                |

## Booking partners

Oct 31, 2026 · @GoAway

- Prices today: Aviasales only (recent flight fares, free).
- Live prices: KAYAK (flights, hotels, cars) and Agoda (hotels), both after approval.
- Everyone else: affiliate links only. Travelpayouts also gives a links API that turns any brand page into our tracked link.
- Commissions via Travelpayouts, read 31 Oct 2026.

### 1. Allowed in our app and website

| Brand               | Booking services (our commission)                               | Via Travelpayouts                           | Direct program?                             | Direct gives                                     | To join directly                    |
| ------------------- | --------------------------------------------------------------- | ------------------------------------------- | ------------------------------------------- | ------------------------------------------------ | ----------------------------------- |
| **Aviasales**       | Flights 1.1–1.3%                                                | **Recent flight prices** (Data API) + links | No: Travelpayouts is Aviasales' own program | —                                                | —                                   |
| **Trip.com**        | Hotels 5.5%, flights 1%, cars 4.5%, trains 1.8%, transfers 4.5% | Links                                       | Yes (trip.com/partners)                     | Links, search box, banners; no API, no prices    | Free sign-up; up to 7% with volume  |
| **DiscoverCars**    | Cars: 54% of their revenue; 365-day cookie                      | Links                                       | Yes (discovercars.com/affiliate)            | Links, widget; XML API with prices case by case  | Free sign-up; API by agreement      |
| **EconomyBookings** | Cars \~5.5%                                                     | Links                                       | Yes (economybookings.com/affiliate)         | Links; API and white label for advanced partners | Free sign-up; API by agreement      |
| Traveloka           | Hotels 3.6%, flights 0.36–0.7%, activities 3.6%                 | Links                                       | Via networks only (Involve Asia, Impact)    | Links                                            | Free; any website or social account |
| QEEQ                | Cars 5%                                                         | Links                                       | No official program found                   | —                                                | —                                   |
| GetRentacar         | Cars from private owners 10%                                    | Links                                       | No official program found                   | —                                                | —                                   |
| Localrent           | Cars 7.5–10% (\~25 countries)                                   | Links                                       | No official program found                   | —                                                | —                                   |

### 2. Allowed on our website only

| Brand           | Booking services (our commission)                                               | Via Travelpayouts          | Direct program?                              | Direct gives                                                                     | To join directly                             |
| --------------- | ------------------------------------------------------------------------------- | -------------------------- | -------------------------------------------- | -------------------------------------------------------------------------------- | -------------------------------------------- |
| **Agoda**       | Hotels 6% (7% to 31 Dec), flights 1% and activities 4% in Asia-Pacific; no cars | Links (site 2+ months old) | Yes (partners.agoda.com): **applied 31 Oct** | Links, search box, banners, hotel data file; **Lite API with live hotel prices** | Original travel content; \~7-day review      |
| **Booking.com** | Stays 4% (5% to 31 Dec), cars 3–5%, flights €1.5 each, attractions 3%           | Links (site 2+ months old) | Only through CJ network                      | Links, banners; Demand API with live prices only for contracted partners         | CJ sign-up; signed contract for the API      |
| **Expedia**     | Hotels 3.6%, cars 1.35%, packages 1.8%; flights 0%                              | Links                      | Yes (Expedia Group creator program)          | Links, widgets; no API (Rapid API is for business partners)                      | Free; pays 4% hotels; India site not covered |
| Hotels.com      | Hotels 3.6%, rentals 1.8%, cars 1.35%                                           | Links                      | Yes (same Expedia Group program)             | As Expedia                                                                       | As Expedia                                   |
| Vrbo            | Holiday rentals 1.8% (4.5% to 31 Dec)                                           | Links                      | Yes (same Expedia Group program)             | As Expedia                                                                       | As Expedia                                   |
| Vio.com         | Hotel price comparison: 40–64% of Vio's revenue                                 | Links                      | No public program found                      | —                                                                                | —                                            |
| Kiwi.com        | Flights 3%                                                                      | Links                      | Yes (partners.kiwi.com)                      | Links; live flight API needs 50,000 monthly users                                | Complete travel site                         |
| Hostelworld     | Hostels \~5–7%                                                                  | Links                      | Yes (via Partnerize)                         | Links (API not checked)                                                          | Free; travel website or account              |
| Klook           | Activities 5%, hotels (8% to 31 Dec)                                            | Links                      | Yes (affiliate.klook.com)                    | Links, widgets (API not checked)                                                 | Free; travel-focused audience                |
| AutoEurope      | Cars 4.4–8%                                                                     | Links                      | Yes (autoeurope.co.uk)                       | Links (API not checked)                                                          | From 5% direct                               |

### 3. Not on Travelpayouts

| Brand     | Booking services                                  | Via Travelpayouts | Direct program?                                           | Direct gives                                                                                          | To join directly                                                   |
| --------- | ------------------------------------------------- | ----------------- | --------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| **KAYAK** | Flights, hotels, cars, packages (app and website) | Not available     | Yes, the only route: **applied 31 Oct, sandbox received** | Links, search box, widgets, white label (limited); **API with live prices** for flights, hotels, cars | Production API by case-by-case approval; no user minimum published |

## Roadmap

Where the project stands and what comes next. Tick items as they ship (merged to `develop` and live on dev).
Shared by all three repos; how to ship is in **Releasing** (README).

### Done

- [x] **Phase 1 — Foundation:** repos, branching (`develop`/`main`), CI, secret scanning, Dependabot.
- [x] **Phase 2 — Backend and login:** PostgreSQL, JWT + rotating refresh tokens, Google Sign-In, rate limits,
      email verification and password reset, website and app connected, UI matched across web and app.
- [x] **Phase 3 — Staging:** `api.goawaygo.com` (Cloud Run + Neon), `staging.goawaygo.com` (Cloudflare Pages),
      Resend email, Android builds via `staging.goawaygo.com/download` and Firebase beta (1.0.0 build 4).

### Phase 4 — Features

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

#### 4.0 Foundations

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

#### 4.1 Discover

Decided 2026-10-26. Design and reasons: `backend/**Discover: destination pages, photos and ideas** (backend README).

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

#### 4.2 Wanderbook

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

#### 4.3 Book

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

#### 4.4 AI trip planner

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

#### 4.5 Notifications

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

#### 4.6 Me

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

#### 4.7 Global readiness

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

#### Later sub-phases

A detailed checklist (and that sub-phase's decisions) is added when it starts. Notes so far:

- **Phone sign-in (MVP 2, carried over):** SMS is never free (Firebase: billing required, billed per SMS by country): cap it then.
- Wanderbook photos: R2 bucket `goawaygo-user-media`. Push: Firebase Cloud Messaging (free).

### MVP 2 — Release pipeline and website launch

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

### Phase 5 — Launch

App store launch (a later MVP) · company registration · Play Console + Android developer verification (before the
2027 rule reaches India) · production database on Cloud SQL (the website launched on Neon in MVP 2, 2026-11-13) · Google billing upgraded before the
trial ends (2027-01-14) · DPDP compliance review (substantive obligations from 13 May 2027).

## History

### Website launch (MVP 2, 5.7)

The website goes live for customers on `goawaygo.com`, served from `main` (the `prod` environment). Decided
2026-11-13: launch now; the lawyer review of the legal pages and the native-speaker check of the 6 other languages
come right after launch. Work through the parts in order and tick each box. Commands run in Git Bash; console steps
name the exact menus.

#### A. Before the switch

**Website build** (done in code, 5.7)

- [x] Public pages pre-rendered with their own titles and descriptions; `sitemap.xml` and `robots.txt`; dev, test,
      staging and `pages.dev` copies send `X-Robots-Tag: noindex` (website PR #70).
- [x] 50 destination pages rendered from prod's data during each prod website deploy (#71).
- [x] A website version with both is in prod (v1.11.2, 13 Nov 2026: 50 of 50 destination pages, 59 sitemap entries).

**Database backups** (backend **Deployment** (README), "Database backups")

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

#### B. The switch (about 30 minutes; pick a quiet time)

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

#### C. After the switch

- [x] Google Search Console: property `goawaygo.com` (domain) → Sitemaps → `https://goawaygo.com/sitemap.xml`
      submitted 13 Nov 2026: Success, 59 pages found.
- [ ] Apply to Agoda again (the partner application), now that the site is readable without JavaScript.
- [ ] Lawyer review of the Privacy Policy, Terms and Community Guidelines (decided: right after launch).
- [ ] Native-speaker check of the 6 other languages (decided: right after launch).
- [x] `staging.goawaygo.com` removed from the backend's `CORS_ALLOWED_ORIGINS` and the Google origins (it only
      redirects); the old Pages project `goawaygo-web-frontend` deleted (14 Nov; **History** (README)).
- [x] The disabled Redirect Rule "goawaygo.com to website" deleted.
- [x] Better Stack: the old staging website monitor deleted; status page logo links to `https://goawaygo.com`.
- [x] App: `WEBSITE_URL=https://goawaygo.com` (app PR #76), and release builds use `env/prod.env` reporting as
      `production` (**History** (README) 7.3). Both arrive with the next app build.
- [ ] **Roadmap** (README): tick 5.7 (after Agoda, the lawyer review and the native-speaker check).

### Clean-up after launch

Done 13–14 Nov 2026, after the website launched on `goawaygo.com`. Rule: everything **dev**, **test** and **prod**
use stays; everything else from before (the pre-launch "staging" setup) is deleted. The only website addresses are
`goawaygo.com` (+ `www` redirecting to it), `dev.goawaygo.com` and `test.goawaygo.com`.

#### What stays (who uses what)

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

#### What was deleted or changed

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
  **Conventions and decisions** (README), deployment docs updated.
- **This PC:** stray `dbg.png`, `backend/Artifact`, `backend/Enabling`.

- **Accounts (signed in by the owner):** GitHub app "Cloudflare Workers and Pages" uninstalled and its authorization
  revoked; PostHog project "GoAwayGo Staging" renamed "GoAwayGo" (same project and token; no authorized URLs, no
  integrations); Resend API key "goaway-staging" renamed "goawaygo" (same key; one domain, no webhooks); the last old
  backend repository variable `WEB_BASE_URL` deleted (the prod smoke test falls back to `https://goawaygo.com`).
- **KAYAK:** no portal account yet (sandbox only); nothing to clean. Its key stays for when KAYAK approves.

#### Still to happen

- The testers' installed app (build 12) still links to staging.goawaygo.com (now gone) for Privacy, Terms, Delete my
  data and shared plans; build 13 links to goawaygo.com and reports to Sentry as `production`.

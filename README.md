# GoAwayGo — Backend

REST API for **GoAwayGo**, a travel app. Built with Spring Boot and PostgreSQL.

Frontends: [app](https://github.com/goawaygoofficial/goawaygo_app_frontend) (Flutter) ·
[web](https://github.com/goawaygoofficial/goawaygo_web_frontend) (React).

> **Status (beta):** accounts (email/password, Google Sign-In, email verification, password reset), health check,
> error tracking and the outside-API toolkit are live on staging. Travel features arrive from Phase 4.1 (`ROADMAP.md`).

## Documentation

New here? Read these in order:

| File                    | What it's for                                                                           |
| ----------------------- | --------------------------------------------------------------------------------------- |
| `README.md` (this file) | What this part does and how to run it locally                                           |
| `CONTRIBUTING.md`       | Branches, commits, PRs and checks                                                       |
| `FOUNDATIONS.md`        | Every service and tool GoAwayGo uses, what it's for, free limits, where it's configured |
| `LINKS.md`              | Every dashboard and console, a weekly checklist, and how to give a new developer access |
| `CLAUDE.md`             | Project facts and conventions (also read by Claude Code)                                |
| `RELEASING.md`          | What happens after a merge, how to release, version numbers, rollbacks                  |
| `ROADMAP.md`            | What's done and what's next                                                             |
| `docs/`                 | Details for this repo (deployment, outside APIs)                                        |

`CLAUDE.md`, `FOUNDATIONS.md`, `LINKS.md`, `RELEASING.md` and `ROADMAP.md` are the same in all three repos.

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
reasons: [`docs/destination-prompts.md`](docs/destination-prompts.md) (Discover) and `ROADMAP.md` 4.2 (Wanderbook);
setup of photo storage, moderation and admins: [`docs/deployment.md`](docs/deployment.md).

| Area                    | Endpoints                                                                                                                                                                                                                                                                                                                                                    |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Places and destinations | `GET /api/discover/places?q=`, `GET /api/discover/destination?placeId=` or `?q=`                                                                                                                                                                                                                                                                             |
| Home banner and ideas   | `GET /api/discover/recent` (signed in), `POST /api/discover/photos`, `GET /api/discover/ideas`                                                                                                                                                                                                                                                               |
| My Trips                | `GET`/`POST /api/me/saved-places`, `DELETE /api/me/saved-places/{id}`                                                                                                                                                                                                                                                                                        |
| Wanderbook profiles     | `GET`/`PUT /api/wanderbook/me`, `PUT /api/wanderbook/me/avatar` and `/cover`, `GET /api/wanderbook/profiles/{id}` (+ `/followers`, `/following`, `/posts`), `GET /api/wanderbook/people?q=`                                                                                                                                                                  |
| Follows and blocks      | `POST`/`DELETE /api/wanderbook/profiles/{id}/follow` and `/block`, `GET /api/wanderbook/me/follow-requests`, `POST`/`DELETE …/follow-requests/{id}`, `DELETE /api/wanderbook/me/followers/{id}`, `GET /api/wanderbook/me/blocked`                                                                                                                            |
| Photos and posts        | `POST /api/wanderbook/photos` (multipart `file`), `POST /api/wanderbook/posts`, `GET`/`PUT`/`DELETE /api/wanderbook/posts/{id}`, `GET /api/wanderbook/feed`                                                                                                                                                                                                  |
| Likes and comments      | `POST`/`DELETE …/posts/{id}/like`, `GET …/posts/{id}/likes`, `GET`/`POST …/posts/{id}/comments`, `DELETE /api/wanderbook/comments/{id}`                                                                                                                                                                                                                      |
| Reports and review      | `POST /api/wanderbook/reports`; admins: `GET /api/admin/wanderbook/reports`, `POST …/reports/{id}/resolve`                                                                                                                                                                                                                                                   |
| Book: flights           | `GET /api/book/airports?q=`, `GET /api/book/flights?from=&to=&depart=&return=&adults=&direct=&currency=&channel=`, `GET /api/book/flights/calendar?from=&to=&month=`                                                                                                                                                                                         |
| Trip plans              | `POST /api/plans` (signed in; 10-40 s), `GET /api/plans`, `GET`/`DELETE /api/plans/{id}`, `POST /api/plans/{id}/days/{n}` (`{"wish"}`), `PUT /api/plans/{id}/share`, `GET /api/plans/shared/{id}` (public)                                                                                                                                                   |
| Notifications           | `GET /api/notifications` (`?before=`), `GET /api/notifications/unread`, `POST /api/notifications/read` (`{"ids"}` or all), `GET`/`PUT /api/notifications/settings`, `PUT /api/notifications/devices` and `POST …/devices/remove` (`{"token","platform"}`), `POST /api/notifications/reminders` (public, daily job), `POST /api/admin/announcements` (admins) |
| Book: stays and cars    | `GET /api/book/stays?place=&checkin=&checkout=&adults=&rooms=&channel=`, `GET /api/book/cars?place=&airport=&pickup=&dropoff=&channel=`                                                                                                                                                                                                                      |

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

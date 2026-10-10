# Goaway — Backend

REST API for **Goaway**, a travel app. Built with Spring Boot and PostgreSQL.

Frontends: [app](https://github.com/goawayofficial/goaway_app_frontend) (Flutter) ·
[web](https://github.com/goawayofficial/goaway_web_frontend) (React).

> **Status:** email/password authentication is implemented. Google Sign-In and password reset are next.

## Tech stack

- Java 17
- Spring Boot 4.1 (Web MVC, Security, OAuth2 Resource Server, Data JPA, Validation)
- PostgreSQL 17 (local: Docker Compose; tests: Testcontainers)
- Flyway for database migrations
- Maven (wrapper included: `mvnw` / `mvnw.cmd`)

## API

| Method | Endpoint                    | Auth          | Purpose                                                                                      |
| ------ | --------------------------- | ------------- | -------------------------------------------------------------------------------------------- |
| `POST` | `/api/auth/signup`          | none          | Create an account (`name`, `email`, `password` of 8-72 characters). Returns the user, `201`. |
| `POST` | `/api/auth/signin`          | none          | Sign in with email and password. Returns tokens.                                             |
| `POST` | `/api/auth/refresh`         | refresh token | Exchange a refresh token for new tokens (rotation).                                          |
| `POST` | `/api/auth/logout`          | refresh token | Revoke the refresh token. `204`.                                                             |
| `GET`  | `/api/users/me`             | access token  | The signed-in user's own profile.                                                            |
| `POST` | `/api/auth/google-signin`   | none          | _Next PR._ Sign in with a Google ID token.                                                   |
| `POST` | `/api/auth/forgot-password` | none          | _Next PR._ Email a password-reset link.                                                      |

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
{ "status": 401, "error": "invalid_credentials", "message": "Incorrect email or password." }
```

Validation errors (`400`, `validation_failed`) also include `fieldErrors`, for example
`{"email": "Enter a valid email address."}`.

## Getting started

### Prerequisites

- JDK 17+
- Docker Desktop (runs the local database and the tests' database)

### 1. Clone

```bash
git clone https://github.com/goawayofficial/goaway_backend.git
cd goaway_backend
```

### 2. Create `.env`

Copy `.env.example` to `.env` and fill in the three database passwords and the JWT key pair
(the file explains how to generate them). `.env` is gitignored, never commit it.

### 3. Start the database

```bash
docker compose up -d
```

This starts PostgreSQL 17 on `127.0.0.1:5433` (only reachable from your machine). On first start it creates the
`goaway` database and its roles (see [Database](#database)).

### 4. Run the backend

```bash
./mvnw spring-boot:run        # Windows PowerShell: .\mvnw.cmd spring-boot:run
```

Flyway applies any pending migrations, then the API starts at `http://localhost:8080`.

## Database

### Roles (least privilege)

| Role           | Used by              | Can                                                           |
| -------------- | -------------------- | ------------------------------------------------------------- |
| `postgres`     | Container setup only | Everything (superuser). Never used by the backend.            |
| `goaway_owner` | Flyway migrations    | Create / alter / drop tables in the `goaway` database         |
| `goaway_app`   | The running backend  | Read and write data. **Cannot** create, alter or drop tables. |

Created by [`docker/postgres/init/01-create-roles.sh`](docker/postgres/init/01-create-roles.sh). The tests use the
same script, and `DatabaseSetupTests` verifies these permissions.

### Migrations

Schema changes are SQL files in `src/main/resources/db/migration`, named `V<number>__<description>.sql`
(for example `V2__create_users.sql`). Flyway applies them in order at startup.
**Never edit a migration that has been merged**; add a new one instead.

### Connect with pgAdmin

Add a server: **Host** `localhost`, **Port** `5433`, **Database** `goaway`, **Username** `goaway_owner`
(or `goaway_app` for read/write-only), **Password** from your `.env`.

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

# Goaway — Backend

REST API for **Goaway**, a travel app. Built with Spring Boot and PostgreSQL.

Frontends: [app](https://github.com/goawayofficial/goaway_app_frontend) (Flutter) ·
[web](https://github.com/goawayofficial/goaway_web_frontend) (React).

> **Status:** early stage. The database layer is set up; the API below is what both frontends already call and is still to be implemented.

## Tech stack

- Java 17
- Spring Boot 4.1 (Spring Web MVC, JDBC, DevTools)
- PostgreSQL 17 (local: Docker Compose; tests: Testcontainers)
- Flyway for database migrations
- Maven (wrapper included: `mvnw` / `mvnw.cmd`)

## API (expected by the frontends)

| Method | Endpoint                    | Purpose                          |
| ------ | --------------------------- | -------------------------------- |
| `POST` | `/api/auth/signup`          | Register with email and password |
| `POST` | `/api/auth/signin`          | Sign in with email and password  |
| `POST` | `/api/auth/google-signin`   | Sign in with a Google ID token   |
| `POST` | `/api/auth/forgot-password` | Start password reset             |
| `GET`  | `/api/auth/users/{userId}`  | Get a user's profile             |

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

Copy `.env.example` to `.env` and fill in the three passwords with long random values
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

# Goaway — Backend

REST API for **Goaway**, a travel app. Built with Spring Boot and MySQL.

Frontend: [goawayofficial/goaway_app_frontend](https://github.com/goawayofficial/goaway_app_frontend) (Flutter).

> **Status:** early stage — project skeleton only. The API below is what the Flutter app already calls and still needs to be implemented.

## Tech stack

- Java 17
- Spring Boot 4.1 (Spring Web MVC, Spring Session JDBC, DevTools)
- MySQL (`mysql-connector-j`)
- Maven (wrapper included: `mvnw` / `mvnw.cmd`)

## API (expected by the frontend)

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/api/auth/signup` | Register with email and password |
| `POST` | `/api/auth/signin` | Sign in with email and password |
| `POST` | `/api/auth/google-signin` | Sign in with a Google ID token |
| `POST` | `/api/auth/forgot-password` | Start password reset |
| `GET` | `/api/auth/users/{userId}` | Get a user's profile |

## Getting started

### Prerequisites
- JDK 17+
- MySQL 8+ running locally (or a remote MySQL database)

### 1. Clone
```bash
git clone https://github.com/goawayofficial/goaway_backend.git
cd goaway_backend
```

### 2. Create the database
```sql
CREATE DATABASE goaway;
```

### 3. Configure local settings
Create `src/main/resources/application-local.properties` (gitignored — never commit it):
```properties
spring.datasource.url=jdbc:mysql://localhost:3306/goaway
spring.datasource.username=YOUR_DB_USER
spring.datasource.password=YOUR_DB_PASSWORD
```

You can use environment variables instead, e.g. `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.

### 4. Run
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```
On Windows PowerShell, use `.\mvnw.cmd` instead of `./mvnw`.

The API starts at `http://localhost:8080`.

## Useful commands

```bash
./mvnw test       # Run tests
./mvnw package    # Build the JAR into target/
```

## Secrets

Never put passwords, keys or tokens in `application.properties`. Use `application-local/dev/prod.properties`, `.env` or environment variables — all are gitignored. See `.gitignore`.

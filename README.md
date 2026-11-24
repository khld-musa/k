# Kraken - Telecom USSD Management Platform

Kraken is a scalable, secure, full-stack application for managing and tracking Telecom USSD gateway nodes. 

## Architecture
- **Backend**: Spring Boot 3.2.x (Java 21)
- **Frontend**: Next.js 14+ (React)
- **Database**: PostgreSQL
- **Cache**: Redis

## Getting Started (Docker / Podman Compose)
The easiest way to run Kraken in a production-like environment is via Docker Compose or Podman Compose.

### Using Docker
1. Ensure Docker and Docker Compose are installed.
2. Review the environment variables in `docker-compose.yml` (especially `JWT_SECRET`, `ENCRYPTION_SECRET`, `DB_PASSWORD`).
3. Run the application:
   ```bash
   docker-compose up -d --build
   ```
4. Access the frontend at `http://localhost:3000`.

### Using Podman
If you prefer Podman, you can use `podman-compose` or the native `podman compose` command (if available via `podman-docker` or similar plugins).

1. Ensure Podman and `podman-compose` are installed.
2. If using rootless Podman, ensure your user has appropriate permissions for volume mounting.
3. Run the application:
   ```bash
   podman-compose up -d --build
   ```
   *Note: If you have the `podman-docker` package installed, you might be able to just use `docker-compose` as an alias.*
4. Access the frontend at `http://localhost:3000`.

## Local Development
For local application development, specific configuration profiles are provided.

### Backend
1. Start local PostgreSQL and Redis servers (or use Docker for just the infra: `docker-compose up -d db redis`).
2. Run the Spring Boot application using the `dev` profile:
   ```bash
   cd backend
   SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
   ```

### Frontend
1. The frontend defaults to pointing at the local backend endpoint:
   ```bash
   cd frontend
   npm install
   npm run dev
   ```

## Security Features (Phase 1-3 Compliant)
Kraken implements rigorous security standards:
- **Authentication**: JWT-based with Refresh Tokens. Tokens are blacklisted in Redis on logout to prevent replay attacks.
- **Rate Limiting**: Login endpoints are backed by a Redis rate limiter (5 fails/minute = 60s block, 10 fails = 15min lockout).
- **Passwords**: Enforces complex passwords (8+ chars, uppercase, lowercase, digit, special char).
- **SSH Host Verification**: Configurable strict host key checking (`ssh.strict-host-key-checking`) with `known_hosts` file support, preventing MITM attacks on SSH sockets.
- **Traceability**: All requests log a unique `X-Correlation-Id` tracking them across standard Application and Audit Logs.
- **Robust Exception Handling**: A Global Exception handler sanitizes errors sent to clients, preventing stack trace leaks while retaining internal visibility.
- **Defense in Depth**: WebSockets and SSE streams are authenticated securely using interceptors or IP-bound single-use session IDs.
- **Frontend Security Headers**: Implements CSP, HSTS, X-Frame-Options, X-XSS-Protection via `next.config.ts`.
- **RBAC**: Strict role-based endpoint protection (`ADMIN`, `OPERATOR`, `VIEWER`, `API_USER`).

## Testing Core Commands
Kraken includes unit testing foundations. Run the suite:
```bash
cd backend
./mvnw test
```

## Infrastructure details
- **Environment Profiles**: Configuration is managed via `application-dev.properties` and `application-prod.properties`.
- **Multi-Stage Docker builds**: Optimizes container sizes for both Next.js (Standalone output) and Spring Boot (clean JRE layering).
- **CI/CD**: Contains `.github/workflows/ci.yml` for automated builds and testing on GitHub.

# ChinaNN Backend

Spring Boot / Java 17 / PostgreSQL. Backend-only repository.

## Deployment limitation

The entire src/main/resources directory is intentionally excluded from Git, including application configuration, database migrations and AI prompts. These files remain only in the local workspace. A checkout of this branch is not a complete deployable application. The Render instructions below require those resources to be supplied separately before building. Do not deploy this source snapshot as-is, or run migrations from it against a database.

## Render
Docker service: Root Directory blank (repository root), Dockerfile ./Dockerfile, Docker Context .
Set SPRING_PROFILES_ACTIVE=render, DB_JDBC_URL, DB_USERNAME, DB_PASSWORD and FRONTEND_ORIGIN in Render environment settings. Do not commit credentials.
Health check: /api/v1/lessons. Run schema migrations separately before starting the runtime service.

## Build and test
Java 17 required. Build: ./mvnw -DskipTests package. Tests: ./mvnw test with an isolated test database configured using TEST_DB_USERNAME and DB_PASSWORD (see src/test/resources/application-test.properties).
The beta Maven profile references ../frontend/dist and is intended only for the original combined workspace; use the default build for this backend-only Render deployment.

## Database
Migration environment: MIGRATION_JDBC_URL, MIGRATION_USERNAME, MIGRATION_PASSWORD.
Command: java -jar target/backend-0.0.1-SNAPSHOT.jar --migrate-only
Provision minimum runtime grants using database/scripts/render-runtime-grants.sql on a new dedicated database after migration. Keep migration credentials out of the web service.

See docs/RENDER-NETLIFY.md for deployment details. Its backend/ paths refer to the original combined workspace; this repository already starts inside backend. The frontend and netlify.toml remain in the separate frontend source/workspace.

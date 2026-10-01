# ChinaNN Backend

Spring Boot / Java 17 / PostgreSQL. Backend-only repository.

## Deployment limitation

Application configuration, database migrations and AI prompts are intentionally excluded from Git. Maven loads them from ../backend-resources (a sibling of the backend checkout), then packages them into the JAR. Keep this directory outside Git and do not place literal passwords or API keys in it; use environment variables for credentials.

Local layout: AppTiengTrung/backend/pom.xml and AppTiengTrung/backend-resources/application.properties, db/migration/, prompts/, static/, templates/. To use a different location, build with ./mvnw -Dapp.resources.directory=/absolute/path/to/backend-resources clean package. Rebuild after changing these resources; the packaged JAR reads its bundled copy.

A Git checkout intentionally contains no private resources. On Render, the Docker image loads configuration and the tutor prompt from runtime Secret Files (see below). Run database migrations separately from a trusted local build that includes the external resources; never run migration mode from the resource-free cloud image.

## Render
Docker service: Root Directory blank (repository root), Dockerfile ./Dockerfile, Docker Context .
Set SPRING_PROFILES_ACTIVE=render, DB_JDBC_URL, DB_USERNAME, DB_PASSWORD and FRONTEND_ORIGIN in Render environment settings. Do not commit credentials.
Add two Secret Files under Environment: app-config.properties (the combined local application.properties and application-render.properties configuration) and tutor-v1.txt (from backend-resources/prompts/tutor-v1.txt). Docker loads them at /etc/secrets/app-config.properties and /etc/secrets/tutor-v1.txt. They are not copied into the image or committed to Git. Keep spring.flyway.enabled=false in the runtime configuration. The Docker user belongs to group 1000 so it can read Render-mounted secret files.
Health check: /api/v1/lessons. Run schema migrations separately before starting the runtime service.

## Build and test
Java 17 required. Build: ./mvnw -DskipTests package. Tests: ./mvnw test with an isolated test database configured using TEST_DB_USERNAME and DB_PASSWORD (see src/test/resources/application-test.properties).
The beta Maven profile references ../frontend/dist and is intended only for the original combined workspace; use the default build for this backend-only Render deployment.

## Database
Migration environment: MIGRATION_JDBC_URL, MIGRATION_USERNAME, MIGRATION_PASSWORD.
Command: java -jar target/backend-0.0.1-SNAPSHOT.jar --migrate-only
Provision minimum runtime grants using database/scripts/render-runtime-grants.sql on a new dedicated database after migration. Keep migration credentials out of the web service.

See docs/RENDER-NETLIFY.md for deployment details. Its backend/ paths refer to the original combined workspace; this repository already starts inside backend. The frontend and netlify.toml remain in the separate frontend source/workspace.

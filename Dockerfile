FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system app && useradd --system --gid app --home /app app
RUN if ! getent group 1000 >/dev/null; then groupadd --gid 1000 render-secrets; fi \
    && usermod -a -G "$(getent group 1000 | cut -d: -f1)" app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/backend-0.0.1-SNAPSHOT.jar /app/app.jar
USER app
ENV SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/secrets/app-config.properties
ENV AI_TUTOR_PROMPT_FILE=/etc/secrets/tutor-v1.txt
EXPOSE 10000
CMD ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/app.jar"]

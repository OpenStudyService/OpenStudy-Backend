# syntax=docker/dockerfile:1
# Runtime image for the single OpenStudy Backend (ECS "service" container). Contract: docs/deployment/README.md
# Build: docker build --platform linux/amd64 --build-arg SOURCE_COMMIT=$(git rev-parse HEAD) -t openstudy-backend:<commit> .
# Base images are pinned by multi-arch index digest (eclipse-temurin 21.0.12.1+1, Ubuntu noble, checked 2026-10-28).

FROM eclipse-temurin:21-jdk-noble@sha256:57424b1d8eca9c4cf8a4fe226207027911f08d6758628891bf59fcfb53777029 AS build
WORKDIR /workspace
# .dockerignore is an allowlist: only the wrapper, build scripts and src/main reach this stage.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon --version
COPY src/main src/main
# Tests need Docker (Testcontainers) and run outside the image build: ./gradlew test bootJar
RUN ./gradlew --no-daemon bootJar && cp build/libs/openstudy-backend-*-SNAPSHOT.jar /workspace/app.jar

# Amazon RDS CA bundle as a separate PKCS12 trust store for the JDBC URL only (sslMode=VERIFY_IDENTITY).
# It holds public CA certificates; the store password is not a secret.
ARG RDS_CA_BUNDLE_URL=https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
RUN curl -fsSL "$RDS_CA_BUNDLE_URL" -o /tmp/rds-ca.pem \
    && sha256sum /tmp/rds-ca.pem \
    && mkdir /tmp/rds && cd /tmp/rds \
    && awk '/-----BEGIN CERTIFICATE-----/{n++} n{print > ("ca-" n ".pem")}' /tmp/rds-ca.pem \
    && for cert in ca-*.pem; do \
         keytool -importcert -noprompt -alias "rds-${cert%.pem}" -file "$cert" \
           -keystore /workspace/rds-truststore.p12 -storetype PKCS12 -storepass openstudy-public-ca >/dev/null; \
       done \
    && keytool -list -keystore /workspace/rds-truststore.p12 -storetype PKCS12 -storepass openstudy-public-ca | grep -c trustedCertEntry

FROM eclipse-temurin:21-jre-noble@sha256:cd35bb33859641c5374415826a5951845104093eaa401cbe9d3d9023444f1c13
ARG SOURCE_COMMIT=unknown
LABEL org.opencontainers.image.title="openstudy-backend" \
      org.opencontainers.image.source="https://github.com/OpenStudyService/OpenStudy-Backend" \
      org.opencontainers.image.revision="${SOURCE_COMMIT}"
RUN groupadd --system --gid 10001 openstudy && useradd --system --uid 10001 --gid openstudy --no-create-home openstudy
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
COPY --from=build /workspace/rds-truststore.p12 /app/rds-truststore.p12
# Plain copy of the migrations packaged in app.jar, for the single migration runner outside the server (Infra #1).
COPY src/main/resources/db/migration /app/db/migration
# No credentials, .env or OWNER values are baked in. DB_URL, DB_USERNAME, DB_PASSWORD and TRUSTED_PROXIES come from
# the task environment; the prod profile fails startup when any of them is missing.
ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0 -XX:+ExitOnOutOfMemoryError"
USER openstudy:openstudy
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health >/dev/null || exit 1
# Exec form: java is PID 1 and receives SIGTERM directly for graceful shutdown.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

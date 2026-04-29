# syntax=docker/dockerfile:1.7

# ─── Stage 1: build the bootJar ──────────────────────────────────────────────
FROM gradle:8.7-jdk17-alpine AS builder

WORKDIR /app

# Copy build-script first so the dependency-resolution layer caches when only
# source changes. (Gradle picks up new deps when build.gradle changes.)
COPY build.gradle settings.gradle ./
# Pre-resolve dependencies so the next layer can be cached while sources change.
RUN gradle dependencies --no-daemon || true

COPY src ./src
RUN gradle bootJar -x test --no-daemon

# ─── Stage 2: minimal JRE runtime ────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime

# curl is needed for the HEALTHCHECK; alpine doesn't ship it by default.
RUN apk add --no-cache curl

# Run as a non-root user — never run a Java service as root in a container.
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

WORKDIR /app
# Spring Boot's bootJar produces a single fat jar in build/libs/. The exact
# filename includes the project version (e.g. backend-0.0.1-SNAPSHOT.jar) —
# glob and rename so the runtime command stays stable.
COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

# /actuator/health is permitAll in SecurityConfig and is the canonical liveness
# probe. -f makes curl exit non-zero on HTTP error.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# Multi-stage Dockerfile for checkout-svc
# ---------- Stage 1: Shared security-common builder ----------
# Inlined from backend/Dockerfile.sec-shared rather than depending on a
# separately pre-built "sec-shared" image. The old approach only worked
# inside docker-compose, via Compose-specific "additional_contexts" wiring
# (docker-compose.yaml's sec-shared service + each service's
# additional_contexts: sec-shared: service:sec-shared) -- a plain
# "docker build" (manual, CI, or an ECR build step for EKS) had no idea
# what "FROM sec-shared" meant and failed outright trying to resolve it as
# a registry image ("pull access denied, repository does not exist").
# Inlining it as this file's own first stage makes a bare
# "docker build -f backend/checkout-svc/Dockerfile ." (context = repo root) work
# identically everywhere, with no prior build step and no
# docker-compose-only mechanism involved. Docker's own layer cache still
# dedupes this stage across all 9 services' builds as long as pom.xml and
# security-common/'s content haven't changed, so this costs nothing extra
# after the first build.
FROM maven:3.9.16-eclipse-temurin-21-alpine AS sec-shared
WORKDIR /app

# Install the root Maven parent POM
COPY pom.xml ./pom.xml
RUN mvn -B -N install

# Build and install security-common into this image's local Maven repository
COPY backend/security-common/pom.xml security-common/pom.xml
COPY backend/security-common/src security-common/src

RUN mvn -B -f security-common/pom.xml clean install -DskipTests

# ---------- Stage 2: Build ----------
FROM sec-shared AS build
WORKDIR /app

COPY backend/checkout-svc/pom.xml checkout-svc/pom.xml
COPY backend/checkout-svc/src checkout-svc/src
RUN mvn -B clean package -DskipTests -f checkout-svc/pom.xml

# ---------- Stage 3: App ----------
FROM gcr.io/distroless/java21-debian13:nonroot AS app
WORKDIR /app

COPY --from=build /app/checkout-svc/target/checkout-svc.jar checkout-svc.jar
EXPOSE 11007

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:InitialRAMPercentage=50", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/checkout-svc.jar"]

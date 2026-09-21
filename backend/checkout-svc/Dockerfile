# Multi-stage Dockerfile for checkout-svc
#
# Build context = REPO ROOT:  docker build -f backend/checkout-svc/Dockerfile .
#
# ---------- Stage 1: Build ----------
# security-common (the shared JWT/CORS/rate-limit library) is compiled and
# installed into the local Maven repository in the same RUN that builds the
# service. That is deliberate: the Maven cache mount below replaces
# /root/.m2 for the duration of the RUN, so anything installed there by an
# EARLIER layer would be invisible to it.
#
# The cache mount (BuildKit, on by default in current Docker) keeps downloaded
# dependencies between builds, so a source-only change no longer re-downloads
# the whole Spring dependency tree. The cache id is per service so parallel
# service builds never write to the same local repository at the same time.
# The mount is a build-time cache only: nothing in it ends up in the image.
FROM maven:3.9.16-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY pom.xml ./pom.xml
COPY backend/security-common/pom.xml security-common/pom.xml
COPY backend/security-common/src security-common/src
COPY backend/checkout-svc/pom.xml checkout-svc/pom.xml
COPY backend/checkout-svc/src checkout-svc/src

RUN --mount=type=cache,id=catalogix-m2-checkout-svc,target=/root/.m2 \
    mvn -B -N install \
    && mvn -B -f security-common/pom.xml clean install -DskipTests \
    && mvn -B -f checkout-svc/pom.xml clean package -DskipTests

# ---------- Stage 2: Runtime ----------
FROM gcr.io/distroless/java21-debian13:nonroot AS runtime
WORKDIR /app

COPY --from=build /app/checkout-svc/target/checkout-svc.jar checkout-svc.jar
EXPOSE 11007

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:InitialRAMPercentage=50", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/checkout-svc.jar"]

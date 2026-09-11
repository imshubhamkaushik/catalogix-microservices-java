# Multi-stage Dockerfile for catalog-svc
# ---------- Stage 1: Build ----------
FROM sec-shared AS build
WORKDIR /app

COPY backend/catalog-svc/pom.xml catalog-svc/pom.xml
COPY backend/catalog-svc/src catalog-svc/src
RUN mvn -B clean package -DskipTests -f catalog-svc/pom.xml

# ---------- Stage 2: App ----------
FROM gcr.io/distroless/java21-debian13:nonroot AS app
WORKDIR /app

COPY --from=build /app/catalog-svc/target/catalog-svc.jar catalog-svc.jar
EXPOSE 11002

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:InitialRAMPercentage=50", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/catalog-svc.jar"]

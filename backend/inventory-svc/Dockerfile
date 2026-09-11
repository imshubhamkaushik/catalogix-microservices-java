# Multi-stage Dockerfile for inventory-svc
# ---------- Stage 1: Build ----------
FROM sec-shared AS build
WORKDIR /app

COPY backend/inventory-svc/pom.xml inventory-svc/pom.xml
COPY backend/inventory-svc/src inventory-svc/src
RUN mvn -B clean package -DskipTests -f inventory-svc/pom.xml

# ---------- Stage 2: App ----------
FROM gcr.io/distroless/java21-debian13:nonroot AS app
WORKDIR /app

COPY --from=build /app/inventory-svc/target/inventory-svc.jar inventory-svc.jar
EXPOSE 11003

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:InitialRAMPercentage=50", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/inventory-svc.jar"]

# Multi-stage Dockerfile for inventory-svc
# ---------- Stage 1: Build ----------
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY pom.xml ./pom.xml
RUN mvn -B -N install

COPY inventory-svc/pom.xml inventory-svc/pom.xml
COPY inventory-svc/src inventory-svc/src
RUN mvn -B clean package -DskipTests -f inventory-svc/pom.xml

# ---------- Stage 2: App ----------
FROM gcr.io/distroless/java21-debian13:nonroot AS app
WORKDIR /app

COPY --from=build /app/inventory-svc/target/inventory-svc.jar inventory-svc.jar
EXPOSE 8085

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:InitialRAMPercentage=50", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/inventory-svc.jar"]

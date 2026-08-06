# Multi-stage Dockerfile for promotions-svc
# ---------- Stage 1: Build ----------
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY pom.xml ./pom.xml
RUN mvn -B -N install

COPY promotions-svc/pom.xml promotions-svc/pom.xml
COPY promotions-svc/src promotions-svc/src
RUN mvn -B clean package -DskipTests -f promotions-svc/pom.xml

# ---------- Stage 2: App ----------
FROM gcr.io/distroless/java21-debian13:nonroot AS app
WORKDIR /app

COPY --from=build /app/promotions-svc/target/promotions-svc.jar promotions-svc.jar
EXPOSE 8087

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:InitialRAMPercentage=50", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/promotions-svc.jar"]

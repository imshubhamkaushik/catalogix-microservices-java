# Multi-stage Dockerfile for payment-svc
# ---------- Stage 1: Build ----------
FROM sec-shared AS build
WORKDIR /app

COPY backend/payment-svc/pom.xml payment-svc/pom.xml
COPY backend/payment-svc/src payment-svc/src

RUN mvn -B clean package -DskipTests -f payment-svc/pom.xml

# ---------- Stage 2: App ----------
FROM gcr.io/distroless/java21-debian13:nonroot AS app

WORKDIR /app

COPY --from=build /app/payment-svc/target/payment-svc.jar payment-svc.jar

EXPOSE 11006

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:InitialRAMPercentage=50", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/payment-svc.jar"]

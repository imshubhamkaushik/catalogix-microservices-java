# Multi-stage Dockerfile for Order Service
# ---------- Stage 1:Build ----------
FROM sec-shared AS build
WORKDIR /app

# Now copy and build the service
COPY backend/checkout-svc/pom.xml checkout-svc/pom.xml
COPY backend/checkout-svc/src checkout-svc/src
RUN mvn -B clean package -DskipTests -f checkout-svc/pom.xml

# ---------- Stage 2: App ----------
FROM gcr.io/distroless/java21-debian13:nonroot AS app
WORKDIR /app

# Copy only the JAR from build stage
COPY --from=build /app/checkout-svc/target/checkout-svc.jar checkout-svc.jar
EXPOSE 11007

# Run the JAR
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:InitialRAMPercentage=50", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/checkout-svc.jar"]

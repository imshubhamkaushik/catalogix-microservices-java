# Multi-stage Dockerfile for Notification Service
# ---------- Stage 1:Build ----------
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Install parent POM so the service POM can resolve it
COPY pom.xml ./pom.xml
RUN mvn -B -N install

# Now copy and build the service
COPY notification-svc/pom.xml notification-svc/pom.xml
COPY notification-svc/src notification-svc/src
RUN mvn -B clean package -DskipTests -f notification-svc/pom.xml

# ---------- Stage 2: App ----------
FROM gcr.io/distroless/java21-debian13:nonroot AS app
WORKDIR /app

# Copy only the JAR from build stage
COPY --from=build /app/notification-svc/target/notification-svc.jar notification-svc.jar
EXPOSE 8084

# Run the JAR
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:InitialRAMPercentage=50", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/notification-svc.jar"]

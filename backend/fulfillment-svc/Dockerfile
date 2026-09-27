FROM maven:3.9.16-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml pom.xml
COPY backend/security-common/pom.xml security-common/pom.xml
COPY backend/security-common/src security-common/src
COPY backend/fulfillment-svc/pom.xml fulfillment-svc/pom.xml
COPY backend/fulfillment-svc/src fulfillment-svc/src
RUN --mount=type=cache,id=catalogix-m2-fulfillment-svc,target=/root/.m2 mvn -B -N install && mvn -B -f security-common/pom.xml clean install -DskipTests && mvn -B -f fulfillment-svc/pom.xml clean package -DskipTests
FROM gcr.io/distroless/java21-debian13:nonroot AS runtime
WORKDIR /app
COPY --from=build /app/fulfillment-svc/target/fulfillment-svc.jar fulfillment-svc.jar
EXPOSE 11012
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/fulfillment-svc.jar"]

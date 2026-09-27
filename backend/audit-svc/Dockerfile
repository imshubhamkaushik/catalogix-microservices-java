FROM maven:3.9.16-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml pom.xml
COPY backend/security-common/pom.xml security-common/pom.xml
COPY backend/security-common/src security-common/src
COPY backend/audit-svc/pom.xml audit-svc/pom.xml
COPY backend/audit-svc/src audit-svc/src
RUN --mount=type=cache,id=catalogix-m2-audit-svc,target=/root/.m2 mvn -B -N install && mvn -B -f security-common/pom.xml clean install -DskipTests && mvn -B -f audit-svc/pom.xml clean package -DskipTests
FROM gcr.io/distroless/java21-debian13:nonroot AS runtime
WORKDIR /app
COPY --from=build /app/audit-svc/target/audit-svc.jar audit-svc.jar
EXPOSE 11015
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/audit-svc.jar"]

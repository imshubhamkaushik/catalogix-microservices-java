FROM maven:3.9.16-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml pom.xml
COPY backend/security-common/pom.xml security-common/pom.xml
COPY backend/security-common/src security-common/src
COPY backend/feature-svc/pom.xml feature-svc/pom.xml
COPY backend/feature-svc/src feature-svc/src
RUN --mount=type=cache,id=catalogix-m2-feature-svc,target=/root/.m2 mvn -B -N install && mvn -B -f security-common/pom.xml clean install -DskipTests && mvn -B -f feature-svc/pom.xml clean package -DskipTests
FROM gcr.io/distroless/java21-debian13:nonroot AS runtime
WORKDIR /app
COPY --from=build /app/feature-svc/target/feature-svc.jar feature-svc.jar
EXPOSE 11016
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/feature-svc.jar"]

# Stage 1: Build
FROM eclipse-temurin:17.0.21.1_2-jdk-noble AS build
WORKDIR /build

COPY .mvn mvnw pom.xml ./
COPY src src

RUN chmod +x mvnw && ./mvnw -B -DskipTests package

# Stage 2: Runtime
FROM eclipse-temurin:17.0.21.1_2-jre-noble
WORKDIR /app

COPY --from=build /build/target/workersafety_server-0.0.1-SNAPSHOT.jar /app/app.jar

USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

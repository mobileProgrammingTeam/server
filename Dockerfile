FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN chmod +x mvnw && ./mvnw -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir /data && chown 10001:10001 /data
COPY --from=build /build/target/workersafety_server-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod SQLITE_DB_PATH=/data/workersafety.db
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

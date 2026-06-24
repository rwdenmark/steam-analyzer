# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q clean package -DskipTests

# Run stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/steam-analyzer-*.jar app.jar
EXPOSE 8080
# STEAM_API_KEY must be provided at runtime, e.g. docker run -e STEAM_API_KEY=...
ENTRYPOINT ["java", "-jar", "app.jar"]

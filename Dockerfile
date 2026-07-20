# --- Build stage: compile and package with Maven (no host Maven/JDK needed) ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Tests need a Docker daemon (Testcontainers), which isn't available during image build.
RUN mvn -B clean package -DskipTests

# --- Runtime stage: slim JRE image running the executable jar ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# Stage 1: Build the Spring Boot JAR using Gradle
FROM gradle:8.10-jdk17-alpine AS builder
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# Stage 2: Run the JAR on a lightweight JRE 17 image
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8091
ENTRYPOINT ["java", "-jar", "app.jar"]

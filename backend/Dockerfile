# Build stage
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline
COPY src ./src
# Build the application skipping tests (tests will run in CI pipeline)
RUN ./mvnw clean package -DskipTests

# Run stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# Install OpenSSH client (needed for JSCH)
RUN apt-get update && apt-get install -y openssh-client && rm -rf /var/lib/apt/lists/*
# Create non-root user
RUN addgroup --system spring && adduser --system --group spring
COPY --from=builder /app/target/*.jar app.jar
# Prepare known_hosts directory
RUN mkdir -p /home/spring/.ssh && chown -R spring:spring /home/spring
USER spring:spring

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

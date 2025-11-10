# === Build Stage ===
FROM maven:3.9.5-eclipse-temurin-17 AS builder

# Set the working directory
WORKDIR /app

# Copy pom.xml first to cache dependencies
COPY pom.xml .

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

# === Run Stage ===
FROM eclipse-temurin:17-jre-alpine

# Set the working directory
WORKDIR /app

# Copy the built jar file from builder stage
COPY --from=builder /app/target/result-generator-1.0.0-jar-with-dependencies.jar app.jar

# Create a directory for Excel output
RUN mkdir -p /data

# Expose the port the app runs on
EXPOSE 8080

# Set the volume for Excel output
VOLUME ["/data"]

# Command to run the application
# PORT environment variable is automatically set by Render
# JAVA_OPTS can be set for memory tuning (e.g., -Xmx512m)
CMD java ${JAVA_OPTS} -jar app.jar
# ====================================================================================
# STAGE 1: Build the application using Gradle
# ====================================================================================
# Use an official Gradle image with a matching JDK version (21)
FROM gradle:8.8.0-jdk21-jammy AS builder

# Set the working directory inside the container
WORKDIR /app

# Copy the Gradle wrapper files
COPY gradlew .
COPY gradle ./gradle

# Copy the build configuration file
COPY build.gradle.kts .
COPY settings.gradle.kts .

# Download dependencies first to leverage Docker's layer caching.
# This step is only re-run if build.gradle.kts changes.
RUN ./gradlew build --no-daemon --stacktrace || return 0

# Copy the entire source code
COPY . .

# Build the application and create the distribution archive, skipping tests
RUN ./gradlew clean build distZip -x test --no-daemon --stacktrace

# ====================================================================================
# STAGE 2: Create the final, lean runtime image
# ====================================================================================
# Use a minimal Java runtime image (JRE - Java Runtime Environment) for ARM64
FROM eclipse-temurin:21-jre-jammy

# Set the working directory
WORKDIR /app

# Install 'unzip', extract the distribution, and clean up to keep the image small
COPY --from=builder /app/build/distributions/*.zip .
RUN apt-get update && \
    apt-get install -y unzip && \
    unzip *.zip && \
    rm *.zip && \
    apt-get purge -y --auto-remove unzip && \
    rm -rf /var/lib/apt/lists/*

# The distribution has a nested folder, e.g., evochora-1.0-SNAPSHOT.
# We need to find its name and move its contents to the current directory.
RUN mv $(ls | grep -v 'lost+found')/* .

# The application runs as a non-root user for better security
RUN useradd --create-home --shell /bin/bash appuser
USER appuser
WORKDIR /home/appuser/app

# Move the application files to the new user's home directory
RUN mv /app/* .

# Create directories for data and configuration that will be mounted as volumes
RUN mkdir -p data/storage data/database config

# Expose the application's ports for external access
# 8081: Javalin Web UI & API
# 8082: H2 Web Console
# 9092: H2 TCP Server
EXPOSE 8081 8082 9092

# The command to run the application using the start script from distZip
CMD ["bin/evochora", "node", "run"]

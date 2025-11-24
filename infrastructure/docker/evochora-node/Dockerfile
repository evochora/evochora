# ====================================================================================
# STAGE 1: Build the application using Gradle with persistent caching
# ====================================================================================
FROM gradle:8.8.0-jdk21-jammy AS builder

ARG RELEASE_TAG
WORKDIR /app

# 1. Copy only the necessary build files
COPY build.gradle.kts settings.gradle.kts ./
COPY gradlew .
COPY gradle ./gradle

# 2. Download dependencies into a persistent cache. This layer is cached by Docker
#    and the Gradle cache itself is persisted across runs via --mount.
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    ./gradlew dependencies --no-daemon

# 3. Copy the rest of the source code
COPY src ./src
COPY assembly ./assembly

# 4. Build the installable distribution (unpacked) instead of a zip archive.
#    This is cleaner for the COPY step in the next stage.
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    ./gradlew installDist -x test --no-daemon --stacktrace

# ====================================================================================
# STAGE 2: Create the final, lean runtime image
# ====================================================================================
FROM eclipse-temurin:21-jre-jammy

# Create a non-root user and its home directory for security
RUN useradd --create-home --shell /bin/bash appuser

# Set the main working directory in the new user's home
WORKDIR /home/appuser/app

# The build stage created a clean distribution in a versioned folder.
# Copy the contents of that folder directly.
COPY --from=builder /app/build/install/evochora-*/* .

# Create directories for data volumes that will be mounted
RUN mkdir -p data/storage data/database config

# Change ownership of all application files to the non-root user
RUN chown -R appuser:appuser /home/appuser/app

# Switch to the non-root user
USER appuser

# Expose the application's ports for external access
# 8081: Javalin Web UI & API
# 8082: H2 Web Console
# 9092: H2 TCP Server
EXPOSE 8081 8082 9092

# The command to run the application using the start script from distZip,
# with the global --config option placed correctly before subcommands.
CMD ["bin/evochora", "--config", "config/evochora.conf", "node", "run"]

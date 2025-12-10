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

# Install runtime dependencies and gosu for privilege dropping.
# gosu is a lightweight su/sudo alternative ideal for containers.
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg gosu && \
    rm -rf /var/lib/apt/lists/*

# Create a non-root user and group with a default ID.
# The entrypoint script will adjust this to match the host user at runtime.
RUN useradd --create-home --shell /bin/bash appuser

# Set the main working directory in the new user's home
WORKDIR /home/appuser/app

# The build stage created a clean distribution in build/install/evochora
# Copy the pre-built application directly from the known path
COPY --from=builder /app/build/install/evochora/ .

# Create directories for data volumes that will be mounted
RUN mkdir -p data/storage data/database config

# Change ownership of all application files to the non-root user
RUN chown -R appuser:appuser /home/appuser/app

# --- NEW: Add the runtime entrypoint script ---
# Copy the entrypoint script and make it executable.
# The path must be relative to the build context (the repository root).
COPY infrastructure/docker/evochora-node/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# Switch to the non-root user. This is a security best practice,
# although the entrypoint itself runs as root to fix permissions.
# USER appuser

# Expose the application's ports for external access
# 8081: Javalin Web UI & API
# 8082: H2 Web Console
# 9092: H2 TCP Server
EXPOSE 8081 8082 9092

# --- NEW: Set the entrypoint ---
# This script will run as root, fix permissions, and then execute the CMD
# as the non-root 'appuser'.
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

# The command to run the application. This is passed as arguments ("$@")
# to the entrypoint script.
CMD ["bin/evochora", "--config", "config/evochora.conf", "node", "run"]

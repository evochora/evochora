# Both base images are pinned by digest and the packages by a snapshot of the Ubuntu archive, so
# that building the same commit again yields the same image. Updating either is a change of its
# own: a new digest, or a new snapshot timestamp.
ARG UBUNTU_SNAPSHOT=20261024T000000Z

# ====================================================================================
# STAGE 1: Build the application using Gradle with persistent caching
# ====================================================================================
# The build runs once, on the platform of the machine that builds the image: what it produces -
# bytecode, the web frontend and jars that carry the native libraries for every platform - is the
# same for every target platform, so each runtime image below takes the same distribution.
FROM --platform=$BUILDPLATFORM gradle:9.8.2-jdk25-resolute@sha256:ca424744a6a45a575f333edea680696d980999c461be38b1574a4c70d5455f24 AS builder

# The release tag becomes the build's version, and the commit its revision: the build context
# carries no .git, so the revision is written to the GIT_REVISION file the build reads instead.
# Without them, as in a local build, the build records "latest" and "unknown".
ARG RELEASE_TAG
ARG GIT_REVISION
WORKDIR /app

# 1. Copy the build files
COPY build.gradle.kts settings.gradle.kts ./
COPY gradlew .
COPY gradle ./gradle
COPY package.json package-lock.json .npmrc ./

# 2. Copy the rest of the source code
COPY src ./src
COPY assembly ./assembly

# 3. Build the installable distribution (unpacked) instead of a zip archive.
#    This is cleaner for the COPY step in the next stage. The Gradle cache and the npm cache of
#    the web frontend are kept across builds on the same machine via --mount; the build resolves
#    only what the distribution needs.
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    --mount=type=cache,target=/app/.gradle/npm-cache \
    if [ -n "$GIT_REVISION" ]; then echo "$GIT_REVISION" > GIT_REVISION; fi && \
    ./gradlew installDist -x test --no-daemon --stacktrace "-PRELEASE_TAG=${RELEASE_TAG:-latest}"

# ====================================================================================
# STAGE 2: Create the final, lean runtime image
# ====================================================================================
FROM eclipse-temurin:25-jre-resolute@sha256:41d9263c0d5b2a74d89af6e29e73b04298bc65e3cb705d03e9e2271ef49fef84

ARG UBUNTU_SNAPSHOT

# Install runtime dependencies and gosu for privilege dropping.
# gosu is a lightweight su/sudo alternative ideal for containers.
# apt knows the snapshot server of archive.ubuntu.com, which serves amd64, but not of
# ports.ubuntu.com, which serves arm64; the snapshot of the archive carries both.
RUN echo 'Acquire::Snapshots::URI::Host::ports.ubuntu.com "https://snapshot.ubuntu.com/ubuntu/@SNAPSHOTID@/";' \
        > /etc/apt/apt.conf.d/50snapshot-ports && \
    apt-get update --snapshot "$UBUNTU_SNAPSHOT" && \
    apt-get install -y --no-install-recommends --snapshot "$UBUNTU_SNAPSHOT" ffmpeg gosu && \
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

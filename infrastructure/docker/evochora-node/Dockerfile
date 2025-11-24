# ====================================================================================
# STAGE 1: Build the application using Gradle
# ====================================================================================
FROM gradle:8.8.0-jdk21-jammy AS builder

ARG RELEASE_TAG

WORKDIR /app

# 1. Copy only the necessary build files
COPY build.gradle.kts settings.gradle.kts ./
COPY gradlew .
COPY gradle ./gradle

# 2. Download dependencies. This layer is cached and only re-run
#    if the build files change. The build will fail but download dependencies.
RUN ./gradlew build --no-daemon || return 0

# 3. Copy the rest of the source code
COPY src ./src
COPY assembly ./assembly

# 4. Build the actual distribution archive. Dependencies are already cached.
#    This command is now much faster.
RUN ./gradlew distZip -x test --no-daemon --stacktrace


# ====================================================================================
# STAGE 2: Create the final, lean runtime image
# ====================================================================================
FROM eclipse-temurin:21-jre-jammy

# Create a non-root user and its home directory for security
RUN useradd --create-home --shell /bin/bash appuser

# Set the main working directory in the new user's home
WORKDIR /home/appuser/app

# Install 'unzip', extract the distribution, and clean up to keep the image small
COPY --from=builder /app/build/distributions/*.zip .
RUN apt-get update && \
    apt-get install -y unzip && \
    unzip *.zip && \
    rm *.zip && \
    apt-get purge -y --auto-remove unzip && \
    rm -rf /var/lib/apt/lists/*

# The distribution has a nested folder, e.g., evochora-1.0-SNAPSHOT. Move contents up.
RUN mv $(ls | grep -v 'lost+found')/* .

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

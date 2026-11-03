# The base image is pinned by digest and the packages by a snapshot of the Ubuntu archive, so
# that building the same commit again yields the same image, with one exception: the list of
# certificate authorities. The snapshot is served over HTTPS only, which apt can use only once
# ca-certificates is installed, so that package comes from the archive as it is on the day of the
# build. Updating the digest or the snapshot is a change of its own.
ARG UBUNTU_IMAGE=ubuntu:resolute@sha256:6a06111c3b0a8600feadb270aec771dc239bf407b25625f93f44ab00e11f4d3c
ARG UBUNTU_SNAPSHOT=20261024T000000Z

# The build context holds the Linux platform distributions, each named by the architecture Docker
# builds for (amd64.tar.gz, arm64.tar.gz), and entrypoint.sh. The Gradle task dockerContext lays it
# out under build/docker; the CI fills it with the archives its start tests have run, so that the
# image carries exactly those.

# ====================================================================================
# STAGE 1: Unpack the distribution of the target architecture
# ====================================================================================
# Unpacking does not depend on the target, so it runs on the platform of the building machine.
FROM --platform=$BUILDPLATFORM ${UBUNTU_IMAGE} AS unpack
ARG TARGETARCH
ADD ${TARGETARCH}.tar.gz /unpack/
# The Java runtime apart from the rest, so that the image carries it in a layer of its own
RUN mv /unpack/evochora-*/jre /jre && mv /unpack/evochora-* /app

# ====================================================================================
# STAGE 2: Create the final, lean runtime image
# ====================================================================================
FROM ${UBUNTU_IMAGE}

ARG UBUNTU_SNAPSHOT

# Install runtime dependencies and gosu for privilege dropping.
# gosu is a lightweight su/sudo alternative ideal for containers.
# ca-certificates comes first, from the current archive over HTTP, as the comment at the top says.
# apt knows the snapshot server of archive.ubuntu.com, which serves amd64, but not of
# ports.ubuntu.com, which serves arm64; the snapshot of the archive carries both.
RUN apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates && \
    echo 'Acquire::Snapshots::URI::Host::ports.ubuntu.com "https://snapshot.ubuntu.com/ubuntu/@SNAPSHOTID@/";' \
        > /etc/apt/apt.conf.d/50snapshot-ports && \
    apt-get update --snapshot "$UBUNTU_SNAPSHOT" && \
    apt-get install -y --no-install-recommends --snapshot "$UBUNTU_SNAPSHOT" ffmpeg gosu && \
    rm -rf /var/lib/apt/lists/*

# Create a non-root user and group with a default ID.
# The entrypoint script will adjust this to match the host user at runtime.
RUN useradd --create-home --shell /bin/bash appuser

# Set the main working directory in the new user's home
WORKDIR /home/appuser/app

# The Java runtime first, in a layer of its own: it changes less often than the application, so
# the layer stays cached across releases. Both are owned by the non-root user as they land:
# changing the owner in a later step would store every file a second time in its own layer.
COPY --from=unpack --chown=appuser:appuser /jre ./jre
COPY --from=unpack --chown=appuser:appuser /app/ .

# The distribution's licenses/ lists what the distribution ships, java-runtime.txt among it; the
# image adds the operating system packages, and this file says where their licenses and sources
# are. The packages come from the archive snapshot they are pinned to.
RUN { echo "Operating system packages: Ubuntu, from the archive snapshot $UBUNTU_SNAPSHOT,"; \
      echo "  except ca-certificates, from the archive as it was when the image was built"; \
      echo "  licenses: /usr/share/doc/<package>/copyright"; \
      echo "  sources:  the source packages of the apt archive https://snapshot.ubuntu.com/ubuntu/$UBUNTU_SNAPSHOT/"; \
    } > licenses/docker-image.txt

# Create directories for data volumes that will be mounted. They, the application directory
# itself and the file written above are owned by the non-root user like the copied files.
RUN mkdir -p data/storage data/database config && \
    chown appuser:appuser . licenses/docker-image.txt && \
    chown -R appuser:appuser data config

# Copy the entrypoint script and make it executable.
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
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

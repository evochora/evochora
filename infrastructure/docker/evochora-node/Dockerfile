# The base image is pinned by digest and the packages the node uses by a snapshot of the Ubuntu
# archive, so that building the same commit again yields the same image. Updating the digest or the
# snapshot is a change of its own.
ARG UBUNTU_IMAGE=ubuntu:resolute@sha256:6a06111c3b0a8600feadb270aec771dc239bf407b25625f93f44ab00e11f4d3c
ARG UBUNTU_SNAPSHOT=20261024T000000Z

# The build context is the directory that holds the Linux platform distributions as the build names
# them: build/distributions locally, the archives its start tests have run in the CI, so that the
# image carries exactly those. entrypoint.sh comes from a second, named context, "entrypoint", the
# directory of this file.
#
# EVOCHORA_VERSION is the version the archives name, the RELEASE_TAG they were built with; without
# one the build names them "latest". Archives of another version beside them are not taken.
ARG EVOCHORA_VERSION=latest

# ====================================================================================
# STAGE 1: Unpack the distribution of the target architecture
# ====================================================================================
# Unpacking does not depend on the target, so it runs on the platform of the building machine.
FROM --platform=$BUILDPLATFORM ${UBUNTU_IMAGE} AS unpack
ARG TARGETARCH
ARG EVOCHORA_VERSION
COPY evochora-${EVOCHORA_VERSION}-linux-*.tar.gz /dist/
# Docker names the architecture it builds for amd64 or arm64, the distributions name their
# platform. The Java runtime is set apart from the rest, so that the image carries it in a layer of
# its own.
RUN case "$TARGETARCH" in \
      amd64) platform=linux-x64 ;; \
      arm64) platform=linux-aarch64 ;; \
      *) echo "No Linux distribution for the architecture $TARGETARCH" >&2; exit 1 ;; \
    esac && \
    mkdir /unpack && tar -xzf /dist/evochora-"$EVOCHORA_VERSION"-"$platform".tar.gz -C /unpack && \
    mv /unpack/evochora-*/jre /jre && mv /unpack/evochora-* /app

# ====================================================================================
# STAGE 2: Create the final, lean runtime image
# ====================================================================================
FROM ${UBUNTU_IMAGE}

ARG UBUNTU_SNAPSHOT

# Install runtime dependencies and gosu for privilege dropping.
# gosu is a lightweight su/sudo alternative ideal for containers.
# The snapshot is served over HTTPS only, which apt can use only with the list of certificate
# authorities that ca-certificates brings, and the base image has none: that list comes from the
# current archive, which the base image reaches over HTTP, and is kept current rather than pinned,
# as a list of trusted authorities should be. The packages are signed by the archive either way.
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
# are. The packages come from the archive snapshot they are pinned to, ca-certificates from the
# archive as it was when the image was built.
RUN { echo "Operating system packages: Ubuntu, from the archive snapshot $UBUNTU_SNAPSHOT;"; \
      echo "  ca-certificates from the archive as it was when the image was built"; \
      echo "  licenses: /usr/share/doc/<package>/copyright"; \
      echo "  sources:  the source packages of the Ubuntu archive, as of the snapshot at https://snapshot.ubuntu.com/ubuntu/$UBUNTU_SNAPSHOT/"; \
    } > licenses/docker-image.txt

# Create directories for data volumes that will be mounted. They, the application directory
# itself and the file written above are owned by the non-root user like the copied files.
RUN mkdir -p data/storage data/database config && \
    chown appuser:appuser . licenses/docker-image.txt && \
    chown -R appuser:appuser data config

# Copy the entrypoint script and make it executable.
COPY --from=entrypoint entrypoint.sh /usr/local/bin/entrypoint.sh
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

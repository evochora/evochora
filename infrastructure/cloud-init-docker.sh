#!/bin/bash
# Cloud-init script for an Evochora production instance on OCI.
#
# Prerequisites:
#   - Ubuntu 24.04 Minimal (aarch64) on OCI
#   - A pre-formatted XFS block volume attached as /dev/sdb1
#   - The 'ubuntu' user exists (default OCI image user)
#
# This script is idempotent and can safely be re-run.
#
# Usage:
#   As cloud-init: oci compute instance launch --user-data-file cloud-init-docker.sh ...
#   Manual:        ssh ubuntu@<ip> 'sudo bash -s' < cloud-init-docker.sh

set -euo pipefail

echo "=== Evochora Instance Setup ==="

# --- Package Installation ---
echo "--- Installing packages ---"
apt-get update -qq
apt-get install -y -qq \
    apt-transport-https \
    ca-certificates \
    curl \
    gnupg \
    lsb-release \
    xfsprogs

# --- Docker Installation ---
if command -v docker &>/dev/null; then
    echo "--- Docker already installed, skipping ---"
else
    echo "--- Installing Docker ---"
    mkdir -p /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    echo \
      "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
      $(lsb_release -cs) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null
    apt-get update -qq
    apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi

# Add ubuntu user to docker group (idempotent)
usermod -aG docker ubuntu

# --- Mount Data Volume ---
echo "--- Mounting data volume ---"
MOUNT_POINT="/data"
DEVICE="/dev/sdb1"

if ! mountpoint -q "$MOUNT_POINT"; then
    mkdir -p "$MOUNT_POINT"

    # Verify the device exists and has an XFS filesystem
    if ! blkid "$DEVICE" | grep -q 'TYPE="xfs"'; then
        echo "ERROR: $DEVICE is not an XFS filesystem. Format it first with: sudo mkfs.xfs $DEVICE"
        exit 1
    fi

    mount "$DEVICE" "$MOUNT_POINT"
    echo "$DEVICE mounted at $MOUNT_POINT"
else
    echo "$MOUNT_POINT already mounted, skipping"
fi

# Ensure fstab entry exists (uses UUID for stability across reboots)
UUID=$(blkid -s UUID -o value "$DEVICE")
if ! grep -q "$UUID" /etc/fstab; then
    echo "UUID=$UUID $MOUNT_POINT xfs defaults,nofail 0 2" >> /etc/fstab
    echo "fstab entry added"
else
    echo "fstab entry already exists, skipping"
fi

# --- Application Directory Structure ---
echo "--- Preparing application directories ---"
APP_DIR="$MOUNT_POINT/app"
mkdir -p "$APP_DIR/evochora-data/storage"
mkdir -p "$APP_DIR/evochora-data/database"
mkdir -p "$APP_DIR/evochora-data/topic"
mkdir -p "$APP_DIR/evochora-data/queue"
mkdir -p "$APP_DIR/config"
chown -R ubuntu:ubuntu "$APP_DIR"

# Symlink ~/app -> /data/app so deployment paths (~/app) remain stable
SYMLINK="/home/ubuntu/app"
if [ -L "$SYMLINK" ]; then
    echo "Symlink $SYMLINK already exists, skipping"
elif [ -d "$SYMLINK" ]; then
    echo "WARNING: $SYMLINK is a real directory, not a symlink. Skipping symlink creation."
else
    ln -s "$APP_DIR" "$SYMLINK"
    chown -h ubuntu:ubuntu "$SYMLINK"
    echo "Symlink $SYMLINK -> $APP_DIR created"
fi

echo "=== Setup complete ==="
echo ""
echo "Next steps:"
echo "  1. Deploy with GitHub Actions (copies docker-compose.yml, Caddyfile, evochora.conf)"
echo "  2. Manually place server-specific files:"
echo "     - ~/app/docker-compose.override.yml"
echo "     - ~/app/config/local.conf"
echo "  3. Run: cd ~/app && docker compose pull && docker compose up -d"

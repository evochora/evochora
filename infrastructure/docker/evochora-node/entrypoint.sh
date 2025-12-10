#!/bin/bash
set -e

# This script is run as root. It ensures that the application's data directories
# are owned by the user that will run the process, as specified by the UID/GID
# environment variables passed from the host.

# Default to 1000 if not set.
TARGET_UID=${UID:-1000}
TARGET_GID=${GID:-1000}

echo "Starting entrypoint script..."
echo "Target User: $TARGET_UID:$TARGET_GID"

# Change the ownership of the application and data directories.
# This is the critical step to allow the non-root user to write to
# mounted host volumes.
echo "Fixing permissions for /home/appuser/app..."
chown -R "$TARGET_UID:$TARGET_GID" /home/appuser/app

echo "Permissions fixed. Switching to user $TARGET_UID:$TARGET_GID and starting application..."

# Drop privileges and execute the main application command as the specified user.
# IMPORTANT: We execute as the TARGET_UID, not as the username 'appuser'.
# This directly starts the process with the correct host user ID.
exec gosu "$TARGET_UID:$TARGET_GID" "$@"

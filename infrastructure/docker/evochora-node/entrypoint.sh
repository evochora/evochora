#!/bin/bash
set -e

# This script is run as root. It prepares the container to run the application
# as a non-root user with the same UID/GID as the host user.

# Default to 1000 if UID/GID are not provided.
# This ensures the script works even outside of the docker-compose setup.
TARGET_UID=${UID:-1000}
TARGET_GID=${GID:-1000}

# Get the current UID and GID of appuser inside the container
CURRENT_UID=$(id -u appuser)
CURRENT_GID=$(id -g appuser)

# If the user/group ID's don't match the target, update them.
if [ "$CURRENT_UID" != "$TARGET_UID" ] || [ "$CURRENT_GID" != "$TARGET_GID" ]; then
    echo "Updating appuser's UID/GID from $CURRENT_UID:$CURRENT_GID to $TARGET_UID:$TARGET_GID"
    
    # Change the group ID for appuser
    groupmod -o -g "$TARGET_GID" appuser
    
    # Change the user ID for appuser and update its home directory ownership
    usermod -o -u "$TARGET_UID" -g "$TARGET_GID" -d /home/appuser appuser

    # Fix ownership of the home directory and application files
    chown -R appuser:appuser /home/appuser
fi

# IMPORTANT: Drop privileges and execute the main application command.
# "gosu" is a lightweight alternative to "su" and "sudo", designed for containers.
# It executes the given command as the specified user, and it does not re-fork,
# so the application becomes PID 1 (or the main process).
exec gosu appuser "$@"

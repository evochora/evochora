#!/bin/bash
set -e

# This script is run as root. It hands the data directories to the user the application runs as
# and starts the application as that user.
#
# The user is the image's own, appuser. The directories of the data are mounted from the host,
# where they can belong to anyone, root among them when Docker has created them.

echo "Starting entrypoint script..."

# Change the ownership of the data directories only.
# We explicitly avoid touching /home/appuser/app because it may contain
# read-only mounts (like evochora.conf) which would cause chown to fail.
echo "Fixing permissions for /home/appuser/app/data..."
chown -R appuser:appuser /home/appuser/app/data

echo "Permissions fixed. Switching to user appuser and starting application..."

# Drop privileges and execute the main application command as that user.
exec gosu appuser "$@"

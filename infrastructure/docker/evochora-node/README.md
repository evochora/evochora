# Demo System Configuration

This directory contains reference configuration files for the manual deployment on the Demo System.

## Files

- **`local.conf`**: Contains environment-specific overrides for the demo server (e.g., database paths, network binding).
- **`docker-compose.override.yml`**: Overrides the default `docker-compose.yml` to mount the `local.conf` and use it at startup.
- **`env.template`**: Template for the `.env` file. Contains environment variables for the demo system, including memory settings (`JAVA_MAX_MEM`).

## Deployment Instructions

These files are **not** automatically deployed by the GitHub Actions workflow to prevent overwriting manual changes on the server. To install or update them:

1. Copy the files to the server (e.g., via `scp`):
   ```bash
   scp infrastructure/docker/evochora-node/local.conf user@server:~/app/local.conf
   scp infrastructure/docker/evochora-node/docker-compose.override.yml user@server:~/app/docker-compose.override.yml
   # Copy env.template to .env (or merge with existing .env)
   scp infrastructure/docker/evochora-node/env.template user@server:~/app/.env
   ```
   *Note: If you have existing secrets in `.env` on the server (like DOMAIN_NAME), make sure to merge them manually instead of overwriting! The demo system needs `JAVA_MAX_MEM=16g` in this file.*

2. Restart the application on the server:
   ```bash
   ssh user@server
   cd ~/app
   docker compose up -d
   ```

## How it works

- The `deploy.yml` workflow only updates `docker-compose.yml` and `evochora.conf`.
- `docker compose` automatically merges `docker-compose.yml` and `docker-compose.override.yml`.
- `docker compose` automatically loads variables from `.env` in the same directory.
- The `override` file mounts `local.conf` and changes the startup command to use it.
- `local.conf` includes `evochora.conf` via `include "evochora.conf"`, ensuring it inherits the base configuration.
- Memory usage is controlled via `JAVA_MAX_MEM` in `.env` (defaults to 8g if not set).

# Cleanup Command Design

## Overview

CLI command to delete simulation runs from Storage, Database, and Topics based on glob patterns.

## Usage

```bash
# Keep runs matching pattern (delete rest)
evochora cleanup --keep "20260117*"

# Delete runs matching pattern (keep rest)
evochora cleanup --delete "20260122*"

# Target specific areas (default: all)
evochora cleanup --keep "20260117*" --storage
evochora cleanup --keep "20260117*" --database --topics

# Execute deletion (default: dry-run)
evochora cleanup --keep "20260117*" --force
```

## Parameters

| Parameter | Description |
|-----------|-------------|
| `--keep <pattern>` | Glob pattern for runs to KEEP (mutually exclusive with --delete) |
| `--delete <pattern>` | Glob pattern for runs to DELETE (mutually exclusive with --keep) |
| `--storage` | Target storage directories only |
| `--database` | Target database schemas only |
| `--topics` | Target Artemis topics only |
| `--force` | Execute deletion (default: dry-run preview) |

## Architecture

```
CleanupCommand.java (PicoCLI)
        │
        ▼
CleanupService.java (Orchestration, Pattern Matching)
        │
        ├── StorageCleaner.java  → FileSystem (rm -rf)
        ├── DatabaseCleaner.java → H2 (DROP SCHEMA CASCADE)
        └── TopicCleaner.java    → Artemis (destroyQueue, deleteAddress)
```

## Run-ID Normalization

Pattern matching always uses normalized form (lowercase, dashes):

- Storage: `20260117-22042059-d59177fc-...` (already normalized)
- Database: `SIM_20260117_22042059_D59177FC_...` → normalize before matching
- Topics: `batch-topic_20260117-22042059-...` → extract runId, then match

## Files

- `src/main/java/org/evochora/cli/commands/CleanupCommand.java`
- `src/main/java/org/evochora/cli/cleanup/CleanupService.java`
- `src/main/java/org/evochora/cli/cleanup/StorageCleaner.java`
- `src/main/java/org/evochora/cli/cleanup/DatabaseCleaner.java`
- `src/main/java/org/evochora/cli/cleanup/TopicCleaner.java`

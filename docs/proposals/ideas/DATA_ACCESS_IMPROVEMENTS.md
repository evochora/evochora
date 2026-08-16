# Data Access Improvements

Collected during the regime diagnostic analysis work (March 2026).
Items needed to move from API-hack-based analysis to clean, scalable data access.

## Database Schema

- [ ] Add `death_tick` column to `organisms` table (currently only available in tick snapshots)

## Analytics Plugins (Parquet via Analyzer API)

- [ ] **Per-organism lifecycle plugin**: One row per organism at death, containing:
  - organismId, parentId, genomeHash, birthTick, deathTick, lifespan
  - avgEnergy, avgEntropy over lifetime
  - offspringCount
  - deathCause (energy depletion vs entropy overflow)
  - Instruction statistics (% PEEK, POKE, failed, etc.)
- [ ] **Muller plot data plugin**: Per-tick genome population counts, structured for direct Muller plot rendering without API sampling hacks
- [ ] **Lineage tree plugin**: Parent-child genome relationships as Parquet, replacing the current per-tick API approach

## Infrastructure

- [ ] Enable read-only H2 TCP access on demo system (evochora.org) for direct SQL queries from notebooks
- [ ] Consider exposing a DuckDB/SQL query endpoint for ad-hoc analysis against tick data

## Current Workarounds

The `regime_diagnostic.ipynb` notebook fetches all ~5,300 tick snapshots via the Visualizer API (one HTTP call per tick). This works but is slow (~4 min) and fragile. The improvements above would make this unnecessary.

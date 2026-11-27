# Data Access Improvements

Collected during the regime diagnostic analysis work (March 2026).
Items needed to move from API-hack-based analysis to clean, scalable data access.

## Database Schema

- [x] Add `death_tick` column to `organisms` table

## Analytics Plugins (Parquet via Analyzer API)

- [x] **Per-organism life table**: the tables `births` and `deaths`, one row per organism in each,
  joined by the organism: parent, genome, birth tick, death tick and cause of death (energy used up
  or entropy above the limit). The lifespan and the number of offspring follow from the two tables.
- [ ] **Per-organism statistics over a lifetime**: average energy and entropy, instruction
  statistics (% PEEK, POKE, failed, etc.)
- [x] **Muller plot data**: the table `genome_population`, the count of every genome at every
  recorded tick
- [x] **Lineage tree**: the table `genome_lineage`, every genome with its parent genome

## Infrastructure

- [ ] Enable read-only H2 TCP access on demo system (evochora.org) for direct SQL queries from notebooks
- [ ] Consider exposing a DuckDB/SQL query endpoint for ad-hoc analysis against tick data

## Current Workarounds

A notebook that needs what an organism did during its life, beyond its birth and its death, has to fetch every sampled tick snapshot through the Visualizer API, one HTTP call per tick. For the regime diagnostic analysis these were ~5,300 snapshots: it worked, but was slow (~4 min) and fragile. The open items above would make this unnecessary.

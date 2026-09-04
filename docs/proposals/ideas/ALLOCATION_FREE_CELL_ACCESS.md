# Allocation-Free Cell Access

**Status: IDEA — not decided. Handover from the tiled-grid work; the design choices at the end are
open and are made with the maintainer before a proposal is written.**

## Problem

The instructions of the virtual machine reach cells through the environment's coordinate-based
accessors, `getMolecule(int... coord)`, `setMolecule(...)`, `getOwnerId(int... coord)`. Each call
normalizes the coordinate into a fresh `int[]` (`Environment.getNormalizedCoordinate`) and wraps
the packed cell value into a `Molecule` object; the target of a step is computed by
`EnvironmentProperties.getTargetCoordinate` / `getNextPosition`, which allocate the result array.
On the instruction path this happens several times per organism tick.

In the profile taken during the tiled-grid work (async-profiler, x86, sparse profile, 10 M ticks,
one thread) the environment accessors were the largest single item of the engine's time:

| Item | Share of engine time |
|---|---|
| `Environment.getMolecule` and what it calls | 25–29 % |
| `Environment.getNormalizedCoordinate` alone | ~7 % |
| `Organism.skipNopCells` (after the tiled layout) | 4.6 % |

On the ARM demo server the tiled layout alone brought −14 % in the sparse profile; on the local x86
machine it was a zero sum, the allocations dominating over locality there. The allocations are the
part of the cost this document is about.

## What the tiled-grid work already provides

- The environment has allocation-free in-range accessors: `getMoleculeIntAt(int[])`,
  `getOwnerIdAt(int[])`, `setMoleculeAt(int[], Molecule)` and the owner-setting overload. They
  take a coordinate that lies inside the world, reject one that does not (including an array of the
  wrong length) with an `IllegalArgumentException`, and do not normalize. The instruction fetch
  already uses `getMoleculeIntAt(organism.getIp())`.
- The layout index never leaves `org.evochora.runtime.model`; every accessor outside takes
  coordinates. Any new accessor keeps that boundary (AGENTS.md, "Opaque Cell Index").
- The two numberings have names: the flat index (row-major, persisted) and the layout index
  (position in the tiled grid). The class documentation of `Environment` defines both.
- `GenomeHasher` still reads through the package-private layout-index API; it could visit through
  `visitCellsOwnedBy` instead and lose one allocation per cell, at the price of an extra
  flat-index conversion and sort per birth. Not a hot path; a candidate for the same pass.

## Call sites

Coordinate-based accessors and allocating target computations outside the environment itself,
counted in the main sources at the end of the tiled-grid work:

| Class | Calls |
|---|---|
| `isa/instructions/StateInstruction` | 18 |
| `isa/instructions/EnvironmentInteractionInstruction` | 11 |
| `isa/instructions/ConditionalInstruction` | 9 |
| `services/Disassembler` | 7 |
| `model/Organism` | 7 |
| `worldgen/GeneDeletionPlugin` | 5 |
| `worldgen/GeyserCreator` | 4 |
| `spi/DeathContext`, `datapipeline/services/SimulationEngine` | 3 each |
| `VirtualMachine`, `worldgen/SolarRadiationCreator`, `worldgen/DecayOnDeath`, `model/Molecule`, `datapipeline/resume/SimulationRestorer`, `compiler/CompilerRunner` | 2 each |
| other instructions and plugins (`Stack`, `Nop`, `Data`, `Bitwise`, `Arithmetic`, `Instruction`, `LabelRewritePlugin`, `GeneInsertionPlugin`, `GeneDuplicationPlugin`, `LabelIndex`) | 1 each |

Roughly half of these are on the per-instruction path (the instruction classes, `Organism`,
`VirtualMachine`); the rest run per birth, per death, per tick plugin, or outside the simulation
(disassembler, restorer, compiler) and matter for allocation only, not for speed.

## Constraints that carry over

- Results and persisted bytes must not change. Oracle: the same simulation, same tick hash before
  and after (`TickHashConsumer` over the serialized chunks). Reference values at the end of the
  tiled-grid work on the demo server: sparse profile (`perf_server.conf`, 10 M ticks, 4 threads)
  hash `aab5dc8b50bddf42`, 365–373 s; detailed profile (`perf_server_detailed.conf`, 100 k ticks,
  1 organism, 2000 snapshots) hash `d085ed89f5a2de15`, 42–44 s. `main` before the tiles: 415–431 s
  and 37–38 s. Peak live set sparse / detailed: 375 / 329 MB.
- Every world dimension is a multiple of 32; test worlds smaller than a tile use tile side 1
  through the three-argument `Environment` constructor.
- Preconditions of the environment are checked hard, with an exception in production, not with
  `assert`; only where the type excludes the violation is there no check. The range check on the
  instruction fetch costs about 1.4 % of the sparse profile and was kept for that reason: correct
  architecture and fail-fast outweigh a few percent of runtime.
- No compatibility consideration for old persisted runs or for external implementers of the
  extension interfaces; nobody implements them from outside.
- `Molecule` is a record; the packed `int` is the representation the grid stores, and
  `Molecule.fromInt` / `toInt` convert. An accessor that returns the packed `int` allocates nothing.

## Measurement method

Demo server `ubuntu@evochora.org` (ARM Neoverse-N1, 4 cores), directory `~/bench/cmp`:
`cmp_server.sh <variant>...` runs the sparse profile per variant in a throw-away container from a
pinned JRE image with `-Xms4g -Xmx4g` and a GC log, 180 s cool-down and a load guard of 0.5 before
each run; `cmp_detailed.sh` the same for the detailed profile; `wait_quiet_then_run.sh` starts a
chain after three quiet minutes. A variant is a tree under `~/bench/cmp/trees/<name>` holding
`lib/`, `bin/` and `assembly/` of `./gradlew installDist`, copied with `rsync`. Result lines land
in `progress.txt` / `progress-detailed.txt` with wall-clock seconds and the tick hash. The
demo containers must be idle: open visualizer tabs hold a core for tens of seconds each and spoil
a run. `pkill -f` over ssh with a pattern that occurs in the command itself kills the ssh shell;
send scripts with `scp` and match `[c]mp_`.

Locally: async-profiler with `perf_local.conf`, one thread, 10 M ticks; the x86 machine is shared
with other sessions, so local timings are not reported as fact.

## Open design choices (to be decided with the maintainer)

1. Which accessor shape: normalizing variants next to the in-range ones
   (`getMoleculeIntNormalized(int[] coord)` that wraps toroidally without allocating and rejects a
   bounded-world miss), or a normalize-into-buffer step in the callers followed by the existing
   in-range accessors.
2. Where the reusable coordinate buffers live: per `Organism` (one per data pointer and one
   scratch), per instruction class, or handed in by the caller.
3. Whether `EnvironmentProperties.getTargetCoordinate` / `getNextPosition` get allocation-free
   overloads writing into a caller's buffer, and whether the allocating ones stay for the callers
   outside the tick.
4. Whether instructions keep working with `Molecule` records for the value they read, or switch to
   the packed `int` where they only test the type.
5. Slicing: one slice per call-site group with the tick hash as oracle after each; the instruction
   classes first, because they carry the per-instruction cost.
6. Whether `GenomeHasher` moves onto `visitCellsOwnedBy` in the same pass.

## Related

- Issue #148: in a `BOUND` world the instruction and data pointers must never leave the world; the
  in-range accessors already reject a coordinate outside it, which stops the engine instead of
  reading another cell.
- Issue #151: the scan-line geometry duplicated between the insertion and duplication plugins.

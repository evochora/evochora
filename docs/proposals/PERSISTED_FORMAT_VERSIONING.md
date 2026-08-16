# Versioning of Persisted Formats

**Status: TO BE REVIEWED — verified gap, decisions listed at the end.**

## Problem

evochora writes artifacts that outlive the process producing them: storage batches, the run database
and the run metadata. None of them carries a format version.

Changing an internal format is a deliberate and recurring act, and backward compatibility is
explicitly not maintained. That is not the problem. The problem is that no reader can tell it is
looking at data written by an incompatible build. Old data does not fail loudly — it fails silently,
or with a diagnostic that names a parse offset or a missing column instead of naming the cause.

### Persisted artifacts

| Artifact | Written by | Format |
|---|---|---|
| `batch_%019d_%019d.pb` | `AbstractBatchStorageResource.writeChunkBatchStreaming` | compressed, length-delimited stream of `TickDataChunk` messages |
| Run database | `AbstractDatabaseResource` and the indexers | H2 tables `metadata`, `organisms`, `organism_ticks`, `organism_states`, `topic_messages`, `topic_consumer_group` |
| Run metadata | `MetadataPersistenceService` | `SimulationMetadata` protobuf — run configuration and `ProgramArtifact` |

Parquet analytics output is out of scope: the file format belongs to Apache Parquet and its
compatibility is guaranteed by the format, not by this codebase. `ManifestEntry` is likewise out of
scope — it is a runtime DTO regenerated from plugin definitions on every start, never persisted.

### Cause — protobuf

None of the five `.proto` contracts under `src/main/proto/` declares a version field.
`SimulationMetadata` carries `simulation_run_id`, `resolved_config_json` and the program artifact,
but nothing that identifies the format itself.

Protobuf is designed so that a schema mismatch is not an error:

- A **removed field** yields the type default on read — `0`, empty string, empty list. That is
  indistinguishable from a value that was legitimately absent.
- A **re-used field number** with a compatible wire type parses without complaint and produces a
  wrong value. `int32`, `int64`, `uint32`, `bool` and enums all share the varint wire type, so this
  case is easy to hit unintentionally.
- **Unknown fields are skipped.** The filtered read path does this explicitly:

  ```java
  // AbstractBatchStorageResource.parseChunkWithFilter
  default:
      input.skipField(tag);
      break;
  ```

  Skipping is correct for the field filter, and it is exactly what makes format drift invisible.

Only a field number re-used with an *incompatible* wire type raises an exception, and that exception
reports a parse position, not a version conflict.

### Cause — H2

Every DDL in the codebase is written as `CREATE TABLE IF NOT EXISTS`. Opening a database produced by
an older build therefore succeeds and leaves the old schema in place — no error, no migration. What
happens next depends on the change:

- a column added or removed → the first statement touching it fails at query time with a
  driver-level `Column not found`, inside whichever service happens to run first
- a column whose **meaning** changed while name and type stayed → no error at any point

The `IF NOT EXISTS` idiom is right for its actual purpose, which is idempotent concurrent
initialisation. It is not a compatibility check and cannot serve as one.

## Scope

The change adds a version stamp and a read-side check. It does not add migration, conversion or any
form of backward compatibility, and it does not alter what the artifacts contain otherwise.

Affected write paths: `AbstractBatchStorageResource`, `AbstractDatabaseResource`,
`MetadataPersistenceService`. Affected read paths: the batch readers, `SimulationRestorer` via the
resume checkpoint, `InspectStorageSubcommand`, and the database readers under
`datapipeline/resources/database/`.

## Solution

One format version for all persisted artifacts of a run, defined once in code, written into every
artifact, verified fail-fast on read.

A single number rather than one per artifact: the artifacts of a run are produced together by one
build and consumed together. Per-artifact versions would encode a compatibility relation that does
not exist in practice, and would turn every format change into a matrix decision. One number keeps
the bump a single-line action, which is what stops the discipline from decaying.

### Where the version is written

**Storage batches** — a `format_version` field on `TickDataChunk`. This makes every batch file
self-describing, which matters because batch files are opened without the run database:
`InspectStorageSubcommand` reads them directly, and the resume path reconstructs a simulation from
the snapshot inside a chunk. Existing files lack the field and read as `0`, which is precisely the
semantics required — `0` means "written before versioning existed".

The cost is one varint per chunk. A chunk carries the cell columns of an entire tick range, so this
is not measurable against the runtime hot path.

**Run database** — a `schema_info` table holding a single row, written when the schema is created and
read whenever an existing database is opened. Its absence identifies a pre-versioning database.

**Run metadata** — the same `format_version` field on `SimulationMetadata`, so the descriptor of a run
states the format of the run it describes.

All three read the same constant. Bumping a format means incrementing it once.

### Read behaviour

| Version found | Reaction |
|---|---|
| absent (protobuf default `0`, or no `schema_info` table) | refuse — artifact predates versioning |
| lower than current | refuse, naming both numbers |
| equal to current | proceed |
| higher than current | refuse — artifact was written by a newer build |

No migration path, no tolerance window, no degraded read-only mode. Old runs remain readable with the
build that wrote them.

The diagnostic must name the artifact path, the version found and the version expected. That message
is the entire value of the change; everything else is bookkeeping.

### Why a version stamp and not compatibility fixtures

The alternative safeguard would be to keep artifacts produced by earlier builds as test fixtures and
assert that current code still reads them. For formats this codebase owns that is the wrong
instrument: it asserts a backward compatibility that is deliberately not maintained, so it fails on
every intentional change, gets repaired by regenerating the fixture, and degrades into ritual within
a few iterations.

The version stamp has the opposite cost profile. A deliberate break costs one incremented constant in
the same commit that breaks the format. It only ever costs anything when someone reads old data with
new code — which is the case where it should.

Fixtures remain the correct instrument for formats owned by a third party that does guarantee
compatibility; see [DEPENDENCY_UPDATE](DEPENDENCY_UPDATE.md).

## Implementation

**Step 1 — Constant and contracts.** A single `PERSISTED_FORMAT_VERSION` constant. Add
`uint32 format_version` to `TickDataChunk` and `SimulationMetadata` with fresh field numbers.

**Step 2 — Storage.** Set the field on write. On read, check the first chunk of a batch file before
any consumer sees it, so a rejected file never partially populates downstream state. The check must
sit in `AbstractBatchStorageResource`, not in the individual readers, so that every path — streaming,
filtered and partial-parse — is covered by one implementation.

**Step 3 — Database.** Create and populate `schema_info` alongside the existing DDL; check it when an
existing database is opened. Note that `topic_messages` and `topic_consumer_group` are queue state
rather than run data, but they live in the same database and share its lifecycle, so one check at
database level covers them.

**Step 4 — Metadata.** Set and check the field in `MetadataPersistenceService` and the metadata
readers.

**Step 5 — Tests.** For each of the three artifacts: a version below current, a version above
current, and the absent case are each rejected with a message naming the artifact and both versions;
the current version passes. These tests are cheap to write because the version can be set
programmatically — they need no stored fixture and therefore never need regenerating.

**Step 6 — Documentation.** Record in AGENTS.md that changing a persisted format requires bumping the
constant in the same commit.

## Decisions required before implementation

1. **Treatment of runs that already exist on disk.**

   Everything written so far is unversioned. Under the read rules above, every existing run becomes
   unreadable the moment this lands.

   Arguments for refusing: it is the honest answer. Those artifacts were written by builds whose
   format is not recorded anywhere, so no reader can establish that they match today's contracts —
   accepting them means guessing, which is the behaviour this proposal exists to remove.

   Arguments for grandfathering "absent" as version 1: existing runs stay usable, and in practice
   the format has not changed since they were written. The cost is that the guarantee starts out
   weaker than it appears — version 1 would mean "either genuinely version 1, or unknown".

2. **Whether an explicit override exists for reading refused artifacts.**

   A CLI flag such as `--ignore-format-version` would let a mismatched artifact be read anyway.

   Arguments for: format breaks are frequent here, and a run that is merely inconvenient to
   reproduce may still be worth inspecting. An explicitly requested override is not a silent
   fallback — the operator states that they accept the risk.

   Arguments against: the results of such a read are unsound by construction, and any number taken
   from them can end up in an analysis without the caveat travelling with it. Regenerating the run
   with the current build is the only outcome that can be trusted.

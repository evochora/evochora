# Simulation Campaigns

**Status: IDEA — not decided.**

A campaign runs many simulations at once, each from its own configuration, and collects all of
them in one place where the visualizer and the analyzer show every run of the campaign. The most
common case is a set of replicates: one configuration, run several times with different seeds, so
that a finding can be told apart from chance. A campaign is more general than that: the runs may
differ in any part of their configuration — the ancestor they start from, the world, the
mutation rates, the sampling — and a different seed is only one such difference.

The same setup is meant to run on any cloud that offers interruptible ("spot", "preemptible")
machines, and on a single machine during development.

## The setup

```
                 ┌──────────────────────────── Hub (permanent) ──────────────────────────┐
                 │  database (index of all runs)                                         │
                 │  Artemis topic broker (batch and metadata notifications)              │
                 │  local S3 service (analytics Parquet, raw data if configured)         │
                 │  node show: visualizer and analyzer over every run of the campaign    │
                 └───────────────────────────────▲───────────────────────────────────────┘
                                                 │ writes
   ┌──────────── Worker (interruptible) ─────────┴────── one container per run ──────────┐
   │  simulation engine → persistence service, metadata persistence service              │
   │  metadata indexer, organism indexer, environment indexer, analytics indexer         │
   │  queue broker stays local                                                           │
   └─────────────────────────────────────────────────────────────────────────────────────┘
```

**Workers** run the whole pipeline for exactly one run, unchanged: the engine, both persistence
services and the whole indexer family. Nothing a worker produces stays on it. Everything goes to
resources that survive the worker: the raw data to a storage resource, the index to the database,
the analytics files to an analytics storage resource, the notifications to the topic broker on the
hub. The queue between engine and persistence service stays in the worker's local broker; what is
lost with it is simulated again on resume.

**The hub** is the one machine that runs all the time. It holds the database, the topic broker and
a local S3 service, and runs `node show` over them. Everything the visualizer and the analyzer read
— the database through `IDatabaseReaderProvider` and the analytics files through
`IAnalyticsStorageRead` — lies on the hub's own disk, so viewing a run costs no network round trip
to a remote store. The raw data is read only by `node resume`, `node fork` and video rendering, not
by `node show`; where it lies does not affect viewing.

**All storage goes through one S3 resource.** Whether a storage role points to the hub's local S3
service or to a cloud object store is a line of configuration. Google Cloud Storage and OCI Object
Storage offer S3-compatible APIs, so the same resource serves every provider.

## Configuration of the runs

Every worker gets the same image, and with it the same code and every ancestor under `assembly/`.
A run differs from the others only in its configuration file: which ancestor it starts from,
which seed, which world, which parameters. A campaign is a base configuration and one small file
per run that includes the base and states only what differs, the way `local.conf` on the demo
system includes `evochora.conf`.

The part of the configuration that connects a run to the hub is not the run's to choose: the
storage, database and broker resources, `pipeline.autoStart`, and the tick at which the run ends.
The campaign tool sets these in the override layer of `ConfigLoader`, which lies above every other
source, so that a run file cannot send a run past the hub.

Telling the runs apart afterwards needs no bookkeeping of its own: every run records its resolved
configuration in its metadata (`resolvedConfigJson`), and the visualizer and analyzer show which
configuration a run came from.

Runs with different configurations need different resources: the world size, `parallelism` and
the sampling interval decide CPU, heap and write rate. The campaign tool derives the machine size
of each run from its configuration instead of giving every run the same one.

## Interruptions

A cloud announces the interruption of a spot machine before it takes it back. The worker's node
then shuts down in order, which takes seconds:

- A service is not interrupted while it is in `ShutdownPhase.PROCESSING`. For the persistence
  service, writing a batch, sending its notification and committing lie in that phase, so a batch
  that was started is always announced.
- Every indexer makes a final commit on stop; the analytics indexer writes its files and
  acknowledges their batches before it stops.
- An indexer does not work off its backlog on stop. What it has not read yet stays in the topic,
  which is why the topic broker lives on the hub and not on the worker.

The campaign tool restarts an interrupted run on a new worker with `node resume --run <id>`. The
engine continues from the last batch in storage; the indexers subscribe to the same run on the
hub's broker and receive the notifications that were still open.

## What has to be built

- **S3 resource** for batch storage and analytics storage, with a configurable endpoint.
- **Database on the hub.** Either a Postgres implementation of the database interfaces, or, as a
  first step, the existing H2 implementation reached over TCP. The second needs two changes: a
  remote client must not send `SHUTDOWN IMMEDIATELY` on close, since over TCP that shuts the
  database down for every other worker and for `node show`; and the environment chunk files that
  `RowPerChunkStrategy` writes to a local directory must land on the hub, for example in a
  directory the hub exports over NFS.
- **Run end.** A run stops by itself at a configured tick and the node exits, so that a finished
  container means a finished run. Today `pauseTicks` only pauses the engine and the node keeps
  running.
- **Interruption watcher.** A small, provider-specific piece in the worker that reads the
  interruption notice from the instance metadata and sends SIGTERM to the node.
- **Campaign tool.** Takes the base configuration and the run files, starts one worker per run,
  sets the hub-facing configuration, keeps the mapping from run to `runId`, and decides on every
  start between `node run` and `node resume --run <id>`. Without `--run`, `node resume` takes the
  newest run in storage, which in a shared storage belongs to some other run.
- **Infrastructure** for the hub and the workers, per provider.

## Open choices

- **Database:** Postgres implementation, or H2 over TCP until it reaches its limits. Runs indexed
  into H2 stay there: the indexers receive batches only through the topic, which keeps them only
  for its journal retention period, so they cannot be indexed again into Postgres later.
- **Raw data:** on the hub's local S3 service, or in a cloud object store.
- **Orchestration:** a campaign tool with one small adapter per provider that starts machines
  with a cloud-init script and the container on them, or Kubernetes, with an indexed job per
  campaign, the hub as a stateful workload, and a per-provider overlay for the cluster, the
  storage class and the labels of the spot node pools. Locally, the first is a Docker Compose
  file with the hub and N workers sharing a volume; the second is the same manifests in kind or
  k3d.

## Pitfalls

- **Interruption without notice.** Clouds issue interruption notices on a best-effort basis, and
  under Kubernetes a pod on a spot node may be killed without a signal, notably when several
  nodes are reclaimed at once. Two windows then open:
  - A batch written but not yet announced: resume takes it as the checkpoint and continues behind
    it, so it is never indexed. Remedy: resume sends the notification for the last batch again;
    the indexers write with `MERGE`, so a repeated batch does no harm.
  - Parquet files written before their batches were acknowledged: the analytics indexer names its
    files by the tick range of its session, and the redelivered batches may be grouped into a
    different range on the new worker. Two overlapping files then exist, and a reader that adds
    the rows of a range counts `SUM` columns twice. Remedy: file names that depend only on the
    batches, not on how a session grouped them.
- **Notice periods differ by provider.** AWS announces two minutes ahead. A Google Cloud spot VM
  gets no dedicated notice by default; it can be set to 120 seconds when the VM is created, and
  after the notice the soft-off shutdown period is up to 30 seconds. OCI announces through instance
  metadata. The container runtime has to give the node enough time on stop.
- **The hub is a single point.** While a campaign runs, the workers can deliver nothing without
  it. Its disk is the campaign's data and needs snapshots.
- **One zone.** Workers and hub belong in the same availability zone: traffic between zones is
  charged, and the hub receives every write of the campaign. Binding the workers to one zone
  narrows the spot capacity they can draw on.
- **Write load on the hub.** The write rate of a run follows from its sampling interval and stays
  small for ordinary runs. Forks that record a stretch densely are the case in which the hub's
  disk and network become the limit.

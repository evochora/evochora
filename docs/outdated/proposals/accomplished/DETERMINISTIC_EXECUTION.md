# Deterministic Execution

**Status: ACCOMPLISHED — implemented on branch `feature/deterministic-execution` (PR #105,
2026-08-20), including the JMH before/after comparison and review fixes.**

This document records verified determinism defects, fixes the *contract* a solution must satisfy,
and — in the [Design](#design) section — the agreed mechanism.

## Problem

Same seed + same configuration + same code does not produce identical runs today. Audited
2026-08-19 on `main` @ `d397e222`; every item verified in source.

1. **Shared RNG consumed from the parallel wave.** Fuzzy label resolution is stochastic when
   `selectionSpread > 0` (`PreExpandedHammingStrategy.java:204-211`; default 50 in
   `reference.conf:1792`, overridden nowhere) and draws from **one** shared
   `SeededRandomProvider` installed at `SimulationEngine.java:448`. The jump instructions that
   trigger it (`JMPI`, `CALL`, `SKJI`, …) are registered parallel-safe
   (`ControlFlowInstruction.java:43`) and execute concurrently in wave 1. Which organism receives
   which draw therefore depends on thread scheduling, and the unsynchronized concurrent access to
   the underlying `Well19937c` state is a data race. Consequence: two runs diverge even at a fixed
   thread count on the same machine.

2. **Resume does not restore all RNG streams.** Only the root provider's state is checkpointed and
   restored (`SimulationRestorer.java:218-222`). The label-matching provider is re-derived from the
   seed at stream offset 0 (`SimulationRestorer.java:223`), and every organism's private RNG
   (`Organism.java:177`, used by `RAND`/`RNDS`/`RBIR`) is likewise re-derived — `OrganismState`
   in `tickdata_contracts.proto` carries no RNG state. A resumed run diverges from an
   uninterrupted one at the first affected draw.

3. **Machine-dependent parallelism.** `runtime.parallelism = 0` resolves to
   `max(1, cores − 2)` (`Simulation.java:631-639`) and caps the `parallelism-scaling` table.
   The effective thread count — and on hosts with ≤ 3 cores even the execution path — is a
   property of the machine, not of the configuration.

4. **Sequential and parallel paths differ semantically.** In `tickSequential` a wave-1 reader
   (`SCAN`/`SNT*`/`SPN*`) sees same-tick environment writes of lower-index organisms; in
   `tickParallel` all wave-1 work completes before any wave-2 write (`Simulation.java:444-515`).
   P = 1 and P > 1 produce different states even without any randomness.

Already deterministic (same audit, do not regress): organism ID assignment (FORK executes in the
sequential wave 2), per-tick organism ordering, conflict-resolution tie-breaking by organism ID,
birth/death handler ordering, genome hashing (sorts entries before hashing).

## Required contract

- **R1 — Reproducibility.** Seed + configuration + code determine the entire trajectory
  bit-identically (environment cells, all organism state, all IDs, all analytics inputs).
- **R2 — Resource invariance.** The trajectory is invariant under execution resources: the value
  of `runtime.parallelism`, the `parallelism-scaling` table, and the machine's core count.
  Rationale: a run must be repeatable on a larger machine *while actually using* the additional
  cores. Parallelism is an implementation resource, not part of the simulated physics. A solution
  that only achieves "deterministic at a fixed thread count" does not satisfy this contract.
- **R3 — Resume neutrality.** A paused-and-resumed run is bit-identical to an uninterrupted one,
  for any number of pauses at any ticks.
- **R4 — Observation neutrality.** Sampling/serialization must not influence the trajectory
  (existing rule; the solution must not weaken it).

Consequences that follow from the contract (constraints, not design choices):

- R2 rules out any "one stream, consumed in execution order" randomness model, however
  thread-safe: if draws are handed out in execution order, the mapping draw → consumer depends on
  scheduling. Any conforming design must attribute each draw to a deterministic logical key
  (which key — tick, organism, position, purpose, … — is a design decision).
- R2 requires a single tick semantics defined independently of scheduling; the divergence in
  defect 4 must be resolved by definition (which visibility is *the* semantics is a design
  decision), not by declaring one path canonical implicitly.
- R3 requires that all randomness state be reconstructible from the checkpoint. That changes the
  checkpoint format — coordinate with
  [PERSISTED_FORMAT_VERSIONING](../../../proposals/PERSISTED_FORMAT_VERSIONING.md) (this would be a natural first
  version bump).
- Old runs are not migrated. They were produced under the current behaviour and remain what they
  are; the experiment record for run 20260402
  ([docs/experiments/RUN_20260402_SELECTIVE_SWEEP.md](../../../experiments/RUN_20260402_SELECTIVE_SWEEP.md))
  documents this explicitly.

## Acceptance tests

The contract is testable without reference to the chosen mechanism, in two tiers.

**Suite tests** (`DeterministicExecutionTest`, `@Tag("unit")`, hand-built minimal scenarios on
`Simulation`, whole class well under a second). Each defect has one scenario that reproduces it
on `main` and becomes the regression guard after the fix:

| Test | Scenario | Defect |
|---|---|---|
| `labelSelection_sameSeedAndParallelism_isReproducible` | 16 organisms, each owning two copies of the same label, jumping every tick with `selectionSpread = 50`; two runs with `parallelism = 4` must yield identical IP trajectories | 1 |
| `labelSelection_isParallelismInvariant` | same scenario, `parallelism = 1` vs `4` | 1, 3 |
| `labelSelection_isResumeNeutral` | one jumper; rebuilt at tick 7 the way `SimulationRestorer` does; trajectory must continue unchanged | 2 |
| `organismRandom_isResumeNeutral` | one organism alternating `SETI`/`RAND`; rebuilt after five draws; subsequent values must match the uninterrupted run | 2 |
| `tickVisibility_isParallelismInvariant` | organism 0 `POKI`s a cell, organism 1 `SCNI`s it in the same tick; what organism 1 reads must not depend on `parallelism` | 4 |
| `tickVisibility_readsObserveStateFromTickStart` | same scenario; organism 1 must read the cell as it was at the start of the tick (added with step 4 to pin the defined semantics) | 4 |
| `rootProvider_rejectsDrawsFromInstructionInterceptor` | an interceptor drawing from the root provider is rejected for every organism, with one and with two threads (added with step 5) | contract |

Observed on `main` @ `d397e222` (2026-08-20): all five fail — the two parallel label tests
diverge at tick 1, the label resume test at tick 8, the `RAND` resume test restarts the value
sequence (`…, 428, 898, 608, …` instead of `…, 428, 973, 666, …`), and the visibility test reads
the written value sequentially but the empty cell in parallel.

**Pipeline-layer resume test** (`ResumeNeutralityTest`, unit, datapipeline): a live simulation is
serialized with `OrganismStateSerializer` (extracted from `SimulationEngine`) and the tick-data
encoder, rebuilt by the real `SimulationRestorer`, and must continue identically — twice resumed,
with one and two threads; plus a field-by-field organism round trip through the runtime's own
accessors. Writing this test exposed and fixed a further defect: stochastic label selection
iterated the index entries of one label value in insertion order, which a rebuilt index does not
reproduce; entries are now kept ordered by flat index on insertion (`addLabel`), so the selection
depends on the environment's content alone.

**Manual acceptance** (not in the suite — a replicating population over many ticks is far too
expensive for it): two CLI runs with the same seed and configuration, and one run paused and
resumed, compared on the persisted tick data. This is performed once after the implementation is
complete and recorded in the proposal's accomplished note.

## Design

### Where randomness is consumed

An audit of every `IRandomProvider` consumer on `main` @ `d397e222` yields exactly three classes:

| Consumer | Stream today | Runs in | Defect |
|---|---|---|---|
| Plugins: mutation (`IBirthHandler`), `DecayOnDeath` (`IDeathHandler`), energy creators (`ITickPlugin`) | root `Well19937c` via `asJavaRandom()` | sequential | none — ordering is deterministic, root state is checkpointed |
| Stochastic label selection (`PreExpandedHammingStrategy`) | `deriveFor("labelMatching", 0)`, one shared stream | wave 1, parallel | 1 and 2 |
| Organism instructions `RAND`/`RNDS`/`RBIR` (`StateInstruction`) | `deriveFor("organism", id).asJavaRandom()` | wave 1, parallel, private per organism | 2 |

`src/main` contains no `IInstructionInterceptor` implementation today, but the interface exists
and runs inside the parallel dispatch; the design must cover it.

### Principle: two kinds of randomness

A single stream hands out values in *arrival order*. In the parallel wave, arrival order is thread
scheduling — so a shared stream cannot satisfy R2 no matter how it is synchronized (see the
contract consequences above). The design therefore separates randomness by *where it is consumed*:

- **Sequential randomness** (plugins outside the parallel wave) keeps the root stream exactly as
  today: one `Well19937c`, checkpointed via `rng_state`, consumed in the deterministic sequential
  order of birth/death handlers and tick plugins.
- **Parallel randomness** (everything executed for a specific organism inside the parallel wave)
  is *computed*, not drawn. The value of the n-th draw an organism makes in a tick is a pure
  function of `(seed, tick, organismId, n)`:

  ```
  tickSeed   = mix( seed ^ mix(tick) )                 // Simulation, once per tick
  streamSeed = mix( tickSeed ^ mix(organismId) )       // OrganismRandom.beginTick, once per organism and tick
  value_n    = mix( streamSeed + n · GOLDEN_GAMMA )    // OrganismRandom.nextLong, n = drawIndex ≥ 1
  ```

  with `mix` the SplitMix64 finalizer (`SplitMix64.mix`, a bijection) and `GOLDEN_GAMMA` the
  SplitMix64 increment. `(tick, organismId, n)` enter injectively, so no two draws in a run share
  an input. `streamSeed` is exposed as `OrganismRandom.tickStreamSeed()`; it is the per-tick
  priority used by [CONFLICT_LOSS_SEMANTICS](CONFLICT_LOSS_SEMANTICS.md).

Why computed randomness satisfies the contract:

- **R1/R2:** the formula asks "which tick, which organism, which draw", never "who arrived first".
  Any thread computing it gets the same value; the thread count is irrelevant.
- **R3:** the draw index resets to 0 at the start of every tick and `tick`, `organismId`, `seed`
  are all known after a resume. Nothing needs to be persisted — **the checkpoint and tick-data formats do not
  change**. The `OrganismState` message appears in every sampled tick, so any per-organism state
  would have been paid on every sample; the computed scheme costs zero bytes.
- **Quality:** SplitMix64 is designed to turn a counter into a statistically sound stream (it is
  the JDK's seed generator for that reason). Feeding it the root generator's current state instead
  of `seed` would add no entropy (that state is itself a function of the seed) while coupling
  every organism's randomness to how often plugins have drawn — rejected for that reason.
  Organism randomness and plugin randomness are deliberately **independent streams**, so an
  experiment that toggles a plugin changes organism behaviour only where the plugin acts.
- **Cost:** a handful of ALU operations per draw, with no shared memory. This replaces
  `java.util.Random` (an `AtomicLong` CAS per draw) and the shared `Well19937c` cache line that
  all worker threads currently contend on. Expected effect on the hot path is neutral to
  positive; this is verified against the JMH baseline before and after, not assumed.

### Components

1. **`OrganismRandom`** (`runtime.model`): a tiny generator holding the organism's salt
   (`mix(organismId)`), the current `streamSeed` and the transient `drawIndex`. API limited to
   what consumers need: `nextInt(bound)`, `nextLong()`, `nextDouble()`, `nextBoolean()`,
   `tickStreamSeed()`. Bounded `nextInt` uses the unbiased multiply-shift reduction (Lemire), not
   modulo. `Organism.getRandom()` returns this type instead of `java.util.Random`; the three call
   sites in `StateInstruction` change accordingly. `Organism.resetTickState()` — the first point
   of the organism's tick work on any path, called from `VirtualMachine.plan` — calls
   `beginTick(simulation.getTickSeed())`. `Simulation` computes `tickSeed` once at the start of
   `tick()`; the field is constant while the tick runs, and worker threads read it after the
   dispatch's synchronisation. The run seed reaches `Simulation` through
   `IRandomProvider.seed()` in `setRandomProvider`, so both production paths (engine, restorer)
   carry it automatically; without a provider (runtime tests) the seed is 0. The mixing function
   lives in `runtime.internal.services.SplitMix64` and is shared with `SeededRandomProvider`.

2. **Label matching** takes the caller's `OrganismRandom` as a parameter of `findTarget`
   (interface, `LabelIndex`, `PreExpandedHammingStrategy`). The strategy is shared by all
   organisms on all threads, so it holds no per-organism state; the random source is part of the
   call, which also makes the former "random provider not set" runtime check unnecessary.
   `ILabelMatchingStrategy.setRandomProvider` and the `randomProvider` field in
   `PreExpandedHammingStrategy` are removed, as are the `deriveFor("labelMatching", 0)` calls in
   `SimulationEngine` and `SimulationRestorer`. The selection algorithm (weighted reservoir
   sampling over own exact matches when `selectionSpread > 0`) is unchanged; each draw is still
   uniform on its bound, so the per-call distribution is identical — only the stream identity
   moves from "global" to "the calling organism".

3. **`IInstructionInterceptor` contract:** an interceptor obtains randomness from
   `InterceptionContext.getOrganism().getRandom()`. The root provider passed to the plugin
   constructor is for sequential hooks only. To make this fail fast rather than a documentation
   rule, `SeededRandomProvider` rejects every draw — also through `asJavaRandom()` — made while
   the calling thread executes the parallel wave (`TickWorkerPool.isInParallelWave()`, a
   thread-local flag set on worker threads and on the simulation thread around its share of the
   wave, including the single-thread case). The check is one thread-local read on the sequential
   plugin path only; organism draws never touch `SeededRandomProvider`.

4. **`Organism.deriveFor("organism", id)`** and the `new Random(id)` fallback in the `Organism`
   constructors disappear (done in step 2). Organisms no longer need the simulation's
   `IRandomProvider` at construction time. Once label matching no longer uses `deriveFor` either
   (step 3), the method is removed from `IRandomProvider` and `SeededRandomProvider`.

### Tick semantics (defect 4)

The defined semantics of a tick is **snapshot semantics**: every organism plans and executes its
wave-1 instruction against the environment as it was at the start of the tick; wave-2 writes are
applied afterwards in organism-index order after conflict resolution; deaths are handled after
both waves in index order. This is already what `tickParallel` does; it is the physically
meaningful reading ("all organisms act simultaneously"), and it is the only reading that is
independent of scheduling.

Consequently `tickSequential` is removed. The former parallel algorithm becomes the single
`planResolveExecute`; wave 1 lives in `planAndExecuteLocal(from, to, …)`, which the worker pool
calls with disjoint ranges when more than one thread is active and the calling thread calls with
the full range otherwise (no pool interaction, the existing zero-allocation `interceptContext` is
reused). The thread decision itself — `runtime.parallelism`, the `parallelism-scaling`
thresholds, `resolveActiveParallelism` — is unchanged. Work per organism is identical to before,
one intermediate list fewer. No organism can observe its index relative to others, so no existing
assembly can depend on the old sequential visibility; the full test suite confirmed that no test
did either.

### `parallelism = 0`

Retained as "auto" (`max(1, cores − 2)`), as is the `parallelism-scaling` table. Once R2 holds
they influence speed only, which is their purpose.

### Checkpoint format

Unchanged. The root `rng_state` remains; no per-organism RNG state is added; the label-matching
stream no longer exists. This proposal therefore does **not** depend on
[PERSISTED_FORMAT_VERSIONING](../../../proposals/PERSISTED_FORMAT_VERSIONING.md) and can land first.

### Verification

The suite tests above (`DeterministicExecutionTest`) are the regression guard; no state hasher
and no engine-level test is added to the suite. Test 1 needs no multi-core runner: `parallelism`
is passed to the `Simulation` constructor and `TickWorkerPool` creates the configured thread
count regardless of available cores.

### Implementation order

1. `DeterministicExecutionTest` against `main` — all five scenarios must fail, documenting the
   defects. (Done 2026-08-20.)
2. `SplitMix64`, `OrganismRandom`; `Organism`/`StateInstruction` switch; per-tick `tickSeed`;
   `IRandomProvider.seed()`. (Done 2026-08-20; `organismRandom_isResumeNeutral` green.)
3. Label matching takes the caller's random (`findTarget(…, OrganismRandom)`); shared stream,
   `setRandomProvider` and `deriveFor` removed. (Done 2026-08-20; all three `labelSelection_*`
   tests green.)
4. Single tick algorithm (`planResolveExecute` / `planAndExecuteLocal`); `tickSequential`
   removed. (Done 2026-08-20; `tickVisibility_*` green, semantics pinned by
   `tickVisibility_readsObserveStateFromTickStart`.)
5. Parallel-wave guard in `SeededRandomProvider`; interceptor contract in
   `IInstructionInterceptor`/`InterceptionContext` JavaDoc. (Done 2026-08-20;
   `rootProvider_rejectsDrawsFromInstructionInterceptor` green for one and two threads.)
6. [CONFLICT_LOSS_SEMANTICS](CONFLICT_LOSS_SEMANTICS.md) (same change set; its priority is
   `OrganismRandom.tickStreamSeed()`). (Done 2026-08-20; `ConflictLossSemanticsTest` 7/7.)
7. Full test suite, the five CLI compile checks, JMH comparison against the baseline measured
   on 2026-08-20 before step 2 (same environment).

# Package Dependency Rules

**Status: TO BE REVIEWED — purpose, target graph and four measures agreed; to be implemented as one
unit.**

## Purpose

This proposal exists for one primary reason: **a dependency must enter the codebase as a decision,
not as a side effect.** Nothing today prevents a new import from silently adding an edge between
top-level packages. An edge somebody weighed and accepted is fine — it can be re-judged later. An
edge that appeared through an incidental import was never judged at all.

Two further purposes were weighed and accepted as secondary. They do not justify the rule; they
decide which of the existing edges are worth changing.

**Portability.** Reimplementing `runtime` in another language is an idea, not a scheduled project.
It is not a reason to restructure now, but it is a reason not to let the runtime's outward surface
grow: every type the runtime borrows from another package is a type a port has to carry along.

**Truthfulness, applied pragmatically.** The package structure asserts something about the system,
and the assertion should hold. The standard is deliberately not "no edges" but **"an edge no wider
than its stated reason"**. `runtime` depending on `compiler` for debugging metadata is acceptable;
the same edge carrying anything beyond that is not.

Explicitly **not** driving this proposal: build incrementality (single-module build, no measurable
effect) and substitutability (nothing here is meant to be swapped at this boundary).

## What the analysis established

The findings below are structural statements about the code, not a description of any particular
revision. Quantities are a snapshot and are marked as such.

### `runtime` → `compiler` is a debugging edge that is one type wide

The entire edge is the single type `compiler.api.ProgramArtifact`. The runtime reads exactly one
member of it — the label-hash-to-name map — at exactly one place, in `ProcedureCallHandler`, while
setting up a procedure call. The resulting name is stored in `Organism.ProcFrame` and read by
nobody inside `runtime`. Its only readers anywhere are in `datapipeline`: the organism state
serializer and the visualizer's view DTO. `ProcFrame` is never compared, hashed or used as a key.

The classification is not an interpretation but is provable from the code: the lookup is guarded
against a missing artifact, so the simulation runs correctly without one — only without names. A
value whose absence does not change behaviour is observation, not mechanism. As a debugging edge it
is acceptable.

**Its width is not.** The compiler type sits in the ISA's central abstract signature,
`Instruction.execute`. Of the concrete instruction classes, only the control-flow one ever touches
the parameter; every other implementation carries it unused. The structure asserts "every
instruction may need the compiler artifact"; the truth is "CALL needs a label name for a trace".
Besides the false assertion, this costs a map lookup on every procedure call and a string reference
in every stack frame, both purely for observation.

### `datapipeline` → `node` has two unrelated causes

**A misplaced process wrapper.** `ServiceManagerProcess` exists solely to plug `ServiceManager` into
node's process model. Its own JavaDoc opens by calling it a Node process — the package placement
contradicts the class's own description. An adapter joining A to B can live on either side; on
node's side it produces `node → datapipeline`, which is the intended direction and already the
heaviest edge in the project.

**Misplaced infrastructure.** `EmbeddedBrokerProcess` holds two roles. The process role is thin:
constructor, server id, start, stop, exposed service. Everything else is a static broker registry
plus the entire Artemis configuration. `datapipeline` uses **only the static half** — it looks up
running servers, parses in-VM broker URLs and reads a size default. It uses nothing of the process.

Decisive for the direction of the fix: **`node` itself never calls that static API.** Every real
consumer of the broker registry is in `datapipeline` or `cli`; `node` references the class only in
a comment. Unlike the runtime edge, this one is not observation but infrastructure on the operating
path, and is therefore genuinely misplaced.

### Orders of magnitude

Import counts between top-level packages under `src/main/java`, to show the relative weight of the
edges. They will drift as the code changes and are not a specification — the statements above are.
A search for fully qualified references without imports found none in `runtime`, so the count is
complete for that edge.

| From → to | Imports |
|---|---|
| `cli` → `datapipeline` 56, `runtime` 12, `compiler` 6, `node` 1 | 75 |
| `node` → `datapipeline` 42, `runtime` 6 | 48 |
| `datapipeline` → `runtime` 40, `compiler` 14, **`node` 4** | 58 |
| `compiler` → `runtime` | 34 |
| **`runtime` → `compiler`** | 15 |

Two cycles: `runtime` ↔ `compiler` and `node` ↔ `datapipeline`.

### Out of scope: intra-`runtime` layering

`runtime.model` imports upward from `Simulation`, `ParallelWave` and `Config`. The
`runtime.model` → `TickWorkerPool` inversion flagged by the architecture review of 2026-08-20 no
longer exists. Intra-runtime layering is a separate question and is not decided here.

## Target graph

```
cli          →  node, datapipeline, compiler, runtime
node         →  datapipeline, runtime
datapipeline →  compiler, runtime
compiler     →  runtime
runtime      →  (nothing)
```

`compiler → runtime` is intended: the ISA is the compiler's target.

One principle underlies both `node`/`datapipeline` measures and belongs in the guidelines, so the
same mistake is not repeated:

> **Process wrappers belong to `node`; domain logic belongs to `datapipeline`.**
> `node` owns the lifecycle; it does not own what runs inside it.

Measure 2 fixes a wrapper on the wrong side, measure 3 fixes domain logic on the wrong side — the
same rule seen from opposite directions.

## Measure 1 — The rule and how it works

An ArchUnit test, tagged `unit`, running with the normal suite. It declares, for each top-level
package, which other top-level packages it may reference.

**How it takes effect.** When an import creates an edge the rule does not permit, the test fails
and names the source class, the target class and the violated rule. From there exactly two paths
exist: withdraw the import, or **change the rule file**. A change to the rule file appears in the
diff and is visible in review.

That is the whole mechanism, and it is the reason the rule is worth having. It prevents nothing. It
forces a new dependency to surface as a decision instead of disappearing into an import line — which
is precisely the stated purpose. Its effectiveness depends on the rule file staying short and
justified: a long list that gets reflexively extended provides no signal.

**Decisions taken:**

*Package level, no type-level exemptions.* Once this proposal is implemented, `runtime → compiler`
is empty and every other edge in the target graph is permitted without restriction — so package
granularity suffices. Permitted edges are deliberately **not** narrowed to individual types. Doing
so would inflate the rule and make editing it routine, destroying the signal the mechanism depends
on. Cycle-freedom plus a short package graph catches the cases that actually hurt.

*No exemption list, no `FreezingArchRule`.* Both exist to let a rule coexist with violations it
tolerates. Since this proposal is implemented as one unit, no violation survives it. The rule is
therefore written in its final form from the start.

*Scope is `src/main/java` only.* Test code legitimately reaches across packages; a rule that fires
there constantly gets switched off.

*The test is the single source of truth.* `.agents/architecture-guidelines.md` explains the target
graph and the process-wrapper principle, and points at the test as the authority. Two independently
maintained copies of the same graph would diverge.

**Implementation order within this proposal:** the test is written **first** and declares the target
graph directly. It is therefore red until measures 2 to 4 are complete, and going green is what
demonstrates they are. This is the same test-first discipline applied elsewhere in this codebase:
the specification is executable and precedes the change.

The cost of that order is accepted knowingly: because the rule runs in the normal suite, the branch
has a failing test for the duration of measures 2 to 4, and the intermediate commits are therefore
not bisectable. The consequence is that the branch is merged only once the rule is green — which is
the same condition as the completion criterion below, so it adds no separate rule to remember.

The only new build dependency is ArchUnit, as a test dependency. It is preferred over a
hand-written import scan, which would miss fully qualified references, annotations and generic
parameters, and over Gradle subprojects, whose cost (build, test fixtures, IDE setup, release
packaging) is disproportionate here and which would require both cycles resolved first in any case.

## Measure 2 — Move the pipeline process wrapper to `node`

`ServiceManagerProcess` moves from `datapipeline` to a package alongside the other node processes.
Nothing about it changes except its location: it remains the adapter that gives `ServiceManager` a
process lifecycle.

**The one real risk:** node instantiates processes reflectively from a class name in the
configuration, so the compiler does not protect this move. The class name appears in the shipped
reference configuration, in a node integration test and in the class's own JavaDoc example, and all
must be updated together.

Configurations outside this repository that name the old class will fail at startup with a
`ClassNotFoundException`. This is accepted as a deliberate breaking rename. No compatibility alias
is provided: a silent fallback would hide exactly the kind of misconfiguration that ought to fail
fast.

## Measure 3 — Move the broker registry to `datapipeline`

`EmbeddedBrokerProcess` is split along the seam that already runs through it. The static half — the
broker registry, in-VM URL parsing, retention state, size defaults and the whole Artemis
configuration — becomes a class in `datapipeline` alongside the messaging resources that use it.
What remains in `node` is the process wrapper: lifecycle, exposed service, delegation.

Consumers to repoint are the Artemis queue and topic resources, their tests, the existing broker
process test, and a JavaDoc link in the CLI's topic cleaner.

This is the only measure that touches the operating path rather than observation, so the Artemis
integration tests are its acceptance criterion.

Afterwards the process wrapper imports from `datapipeline`, which is the intended direction, and the
`node` ↔ `datapipeline` cycle is gone.

## Measure 4 — Remove `ProgramArtifact` from the runtime

The runtime stops resolving label names. A procedure frame keeps only the label hash it already
carries; resolving that hash to a name moves to where the name is consumed — serialization and the
visualizer view.

This is deliberately complete removal rather than narrowing. Taking the parameter out of the ISA
signature alone would leave `Simulation` holding the artifact map, so the edge would survive and the
portability benefit — the reason for touching this at all — would not materialise.

**In `runtime`:** the artifact parameter disappears from `Instruction.execute` and from the
procedure call handler; `ProcFrame` loses its name field; `Simulation` loses the artifact map and
its accessors, and the virtual machine no longer looks an artifact up per instruction. Afterwards
the runtime imports nothing from the compiler.

**In `datapipeline`, no new channel has to be created.** This was verified rather than assumed:
`SimulationEngine` already owns the artifacts independently of the `Simulation`, keyed per program
and populated on both the fresh-compile and the resume path. On the resume path the restorer's
result record already carries the artifact map as its own field, and the engine already consumes it.
The map held by `Simulation` is a redundant second channel for data the engine already has; only the
virtual machine and the metadata builder read it back out, and this measure removes the former.

What remains to be done is therefore small: hand the matching artifact to the serializer alongside
the organism, and have the metadata builder read from the engine's own map instead of from the
simulation.

The engine's artifact map is keyed by program **path** today, and only because the compile loop uses
that key to avoid compiling the same program twice. That is a concern local to initialisation: after
initialisation the field is read solely for a memory estimate. It is therefore keyed by program
**id** on both the fresh and the resume path, which is what the lookups added here need, and which
removes the artificial wrapper the resume path builds today purely to satisfy the path-keyed shape.

**Semantics of a missing name.** Two cases must be distinguished, and the distinction is recorded
here so that neither is later "fixed" into the other.

*A label hash the artifact does not know* yields an empty name, and that is the correct result — not
a fallback. Organisms inherit the program id of their ancestor but their code is mutated, so a
procedure call may target a hash the original program never contained. There is no name to report,
and reporting none is the truthful observation. This is already today's behaviour; measure 4 moves
where it happens, not what happens. It is stated explicitly because the codebase otherwise rejects
silent fallbacks, and without this note a later reader could mistake correct behaviour for a defect
and turn a normal evolutionary outcome into an error.

*No artifact at all for a program id* is different: every organism descends from a configured
program, so this can only be a wiring error. It is caught **once, during engine initialisation**,
where every program entry is required to have an artifact — fail-fast at the place where the mistake
is actually made, and at a cost paid once rather than per organism per sampled tick. It is
deliberately **not** enforced inside the serializer: that class carries an explicit, documented
exception to the fail-fast rule, because aborting an experiment of hundreds of millions of ticks
over a recorded display value would be the greater damage. Should the case nonetheless occur there,
the serializer's established policy applies — report it, record an empty value, let the run
continue.

**Format compatibility.** The persisted procedure-name field is kept and still written during
serialization, so stored runs and the visualizer are unaffected. On restore it is ignored, since the
runtime no longer carries a name. Older checkpoints remain readable.

**Behavioural neutrality.** Name resolution moves from every procedure call to sampled ticks only.
Since the name never influenced execution — provable from the guard against a missing artifact — the
simulation is unchanged, and serialization remains a pure observer.

**Expected side effect, not a claim.** Removing a map lookup from the call path and a string
reference from every stack frame should reduce hot-path work and per-organism heap. This has **not**
been measured and is not a justification for the measure; it is recorded so a benchmark run can
confirm or refute it.

**Test impact.** A substantial number of tests construct artifacts or call `execute` directly and
follow the signature change. Resume and determinism tests that compare organism state need updating
because the procedure frame changes shape.

## Sequence

The proposal is implemented as one unit. Within it:

1. **Measure 1** — write the rule in its final form and record the graph and the process-wrapper
   principle in the guidelines. The test is red from here on.
2. **Measure 2** — move the pipeline process wrapper. Smallest change; the reflective binding is the
   only risk.
3. **Measure 3** — extract the broker registry. Operating path; Artemis integration tests decide.
4. **Measure 4** — remove the compiler artifact from the runtime. Largest blast radius; touches the
   hot path and the persisted state shape.

Each step is implemented and verified on its own, and the rule from step 1 turns green only when the
last one lands. That transition is the completion criterion for the proposal.

## Out of scope

- Any change in behaviour.
- Intra-`runtime` layering.
- Gradle subprojects or JPMS modules. This proposal removes the obstacles, so the question can be
  taken up later on its own merits; it is not decided here.
- Error handling on the messaging path. The Artemis resources treat a missing broker server
  inconsistently, in places continuing silently with defaults. For an in-VM broker URL no external
  server can exist, so a missing one is always misconfiguration or a startup-order error and
  arguably should be fatal. That is a behavioural change and belongs in its own change, not in a
  structural proposal — as does the equivalent finding that `ServiceManager` swallows resource
  initialisation failures, which is tracked separately.

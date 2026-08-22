# Package Dependency Rules

**Status: TO BE REVIEWED — purpose, target graph and five measures agreed; to be implemented as one
unit. Three optional packages are described at the end; whether they are implemented here, split
into their own proposal or kept as issues is decided once the measures are done.**

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
than its stated reason"**.

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

The name is not even consumed where it is produced. It is written on the write path, for every
organism, and read only when someone opens a single organism in the visualizer. The map it is
resolved from carries the intent in its own JavaDoc: *"for reverse lookup in visualizer"* — and the
front-end already performs exactly such a reverse lookup elsewhere, in
`AnnotationUtils.resolveLabelName`. Resolving inside the runtime is therefore a deviation not only
from the package structure but from the documented purpose of the data.

### `datapipeline` → `node` has two unrelated causes

**A misplaced process wrapper.** `ServiceManagerProcess` exists solely to plug `ServiceManager` into
node's process model. Its own JavaDoc opens by calling it a Node process — the package placement
contradicts the class's own description. An adapter joining A to B can live on either side; on
node's side it produces `node → datapipeline`, which is the intended direction and already the
heaviest edge in the project.

**Misplaced infrastructure.** `EmbeddedBrokerProcess` holds two roles. The process role is thin:
constructor, server id, start, stop, exposed service. Everything else is a static broker registry
plus the entire Artemis configuration. The class carries the seam as an explicit separator in its
own source, above the static half: *"Static accessors — used by ArtemisTopicResource and
ArtemisQueueResource"*. `datapipeline` uses **only the static half** — it looks up running servers,
parses in-VM broker URLs and reads a size default. It uses nothing of the process.

Decisive for the direction of the fix: **`node` itself never calls that static API.** Every caller
of the broker registry is in `datapipeline`; `node` references the class only in a comment and `cli`
only in a JavaDoc link. Unlike the runtime edge, this one is not observation but infrastructure on
the operating path, and is therefore genuinely misplaced.

### `compiler` → `datapipeline` carries the pipeline's wire format into the compiler

Four methods in `compiler.api` reference generated Protobuf types:

- `ParamType.fromProtobuf(datapipeline.api.contracts.ParamType)` and `ParamType.toProtobuf()`
- `ParamInfo.fromProtobuf(datapipeline.api.contracts.ParamInfo)` and `ParamInfo.toProtobufBuilder()`

None of them uses an import; the types are written out fully qualified, which is why a search over
import statements does not see this edge.

`ParamType` is a language concept — REF/VAL/LREF/LVAL is parameter semantics of the assembly
language. That this type knows how to translate itself into the storage format of its observer
inverts the dependency in the same way the runtime edge does.

The methods do not encapsulate that knowledge, they fragment it. `datapipeline` already converts
the whole `ProgramArtifact` by hand in `SimulationEngine.convertProgramArtifact` and back in
`SimulationRestorer.convertProtoProgramArtifact` — eleven fields, including `param.name()`. Only
`param.type()` calls back into the compiler, so the conversion of a two-field record is split
across two packages. The `ParamInfo` methods that would have encapsulated it are never called by
anything, in `src/main` or `src/test`.

### Orders of magnitude

References between top-level packages under `src/main/java`, to show the relative weight of the
edges. They will drift as the code changes and are not a specification — the statements above are.

The first column counts import statements. The second counts mentions of the target package outside
import lines; those mix code and JavaDoc, and were resolved individually only where it mattered:
for `runtime` → `compiler` there are none, so that count is complete, and for
`compiler` → `datapipeline` all nine are code.

| From → to | Imports | Outside imports |
|---|---|---|
| `cli` → `datapipeline` 56, `runtime` 12, `compiler` 6, `node` 1 | 75 | 3 |
| `node` → `datapipeline` 42, `runtime` 6 | 48 | 7 |
| `datapipeline` → `runtime` 40, `compiler` 14, **`node` 4** | 58 | 24 |
| `compiler` → `runtime` 34, **`datapipeline` 0** | 34 | 11 |
| **`runtime` → `compiler`** | 15 | 0 |

Three cycles: `runtime` ↔ `compiler`, `node` ↔ `datapipeline` and `compiler` ↔ `datapipeline`.

**No further hidden edge exists.** After `compiler → datapipeline` surfaced, every package pair was
checked for references outside import lines, not just the pair that had produced the surprise. The
edges that would break the target graph if they existed are all empty: `runtime` references nothing
outside its imports at all, `compiler` reaches `datapipeline` in exactly the nine places named above
and neither `node` nor `cli`, `node` reaches neither `cli` nor `compiler`, and `datapipeline` does
not reach `cli`. The target graph is therefore reachable with the five measures below, and the
completion criterion in measure 1 holds. This is a static check and does not replace the first run
of the rule, which sees references that no text search can.

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

One principle underlies both `node`/`datapipeline` measures and belongs in `AGENTS.md`, so the
same mistake is not repeated:

> **Process wrappers belong to `node`; domain logic belongs to `datapipeline`.**
> `node` owns the lifecycle; it does not own what runs inside it.

Measure 2 fixes a wrapper on the wrong side, measure 3 fixes domain logic on the wrong side — the
same rule seen from opposite directions.

## Measure 1 — The rule and how it works

An ArchUnit test, `org.evochora.architecture.PackageDependencyRulesTest`, tagged `unit`, running
with the normal suite. It declares, for each top-level package, which other top-level packages it
may reference. Its own package is deliberately not one of the mirrored production packages: the
test stands above all of them and belongs in none.

**Form of the rule.** One rule per package, written in the direction of the target graph:

```java
noClasses().that().resideInAPackage("org.evochora.runtime..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.evochora.compiler..", "org.evochora.datapipeline..",
        "org.evochora.node..", "org.evochora.cli..")
```

Deliberately not `layeredArchitecture()`. That API expresses permissions the other way round —
`mayOnlyBeAccessedByLayers`, "who may use me" instead of "whom may I use". The target graph in this
document reads in the second direction, and since the test is the authority, anyone comparing the
two would have to invert one of them mentally. That is an avoidable source of error at exactly the
point where precision matters.

A second rule requires the package graph to be free of cycles:

```java
slices().matching("org.evochora.(*)..").should().beFreeOfCycles()
```

It is redundant while the target graph holds, and that is not the case it guards. It guards the
moment someone edits the **rule file**: a new edge that closes a cycle is caught even though the
edited rule permits it. That is precisely the failure mode a short, hand-edited rule list is
exposed to. Its cost is that a genuine violation is reported twice, once per rule — accepted,
because the two reports answer different questions.

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
and `compiler → datapipeline` are empty and every other edge in the target graph is permitted
without restriction — so package granularity suffices. Permitted edges are deliberately **not**
narrowed to individual types. Doing so would inflate the rule and make editing it routine,
destroying the signal the mechanism depends on. Cycle-freedom plus a short package graph catches
the cases that actually hurt.

*No exemption list, no `FreezingArchRule`.* Both exist to let a rule coexist with violations it
tolerates. Since this proposal is implemented as one unit, no violation survives it. The rule is
therefore written in its final form from the start.

*Scope: production code, generated code included.* Test code is excluded — it legitimately reaches
across packages, and a rule that fires there constantly gets switched off. Note that "src/main/java
only" is not expressible in ArchUnit: it reads compiled classes, and the Protobuf plugin compiles
its output into the same `main` class directory, so the exclusion is expressed over test locations
rather than source directories.

Generated Protobuf code is **not** exempt. What the generator emits is determined entirely by
hand-written `.proto` files — every one of them sets `option java_package` explicitly, and the only
imports are the Protobuf library and a sibling contract file. Target package, class names and
referenced types are therefore all under the authors' control, and a violation originating in
generated output would be a violation of a `.proto` definition, fixable at that source like any
other code. Exempting it would also weaken the cycle rule specifically: slices are formed from
imported classes, so an excluded package belongs to no slice and edges leading into it stop counting
as cycle participants — `compiler ↔ datapipeline` would go unreported even though the edge rule
catches it.

*The generated contracts count as `datapipeline`.* This is what makes `compiler → datapipeline` a
real violation rather than a technicality, and it follows from the portability purpose: if the
contracts were a neutral layer that anyone may reference, `runtime` could reference them too, and a
port would have to carry the pipeline's wire format along. Today `runtime` references no contract
class at all, and that property is worth keeping. The counter-position — a Protobuf schema is
language-neutral, so a port could reuse the `.proto` file — does not lead to an exemption in the
test but to a different conclusion: then the schema would not belong in the `datapipeline`
namespace at all. That is a much larger change, touching every `.proto` file and nearly every
`datapipeline` class, and it is not taken here.

*The test is the single source of truth.* The target graph and the process-wrapper principle are
explained in `AGENTS.md`, in its "Architectural Principles" section, as a new subsection ahead of
the per-package ones — the graph spans packages, while those describe one each. That section already
covers exactly the five packages of the graph. The explanation points at the test as the authority;
two independently maintained copies of the same graph would diverge.

`.agents/architecture-guidelines.md` is deliberately **not** the place for the graph itself. Despite
its name it is not architecture documentation but the prompt of a review agent — it is written as
instructions to that agent. It receives one line among its review criteria, so the agent checks
package dependencies against the test.

**Implementation order within this proposal:** the test is written **first**, declares the target
graph directly, and enters the suite immediately. It is therefore red until measure 5 is complete,
and going green is what demonstrates the measures are done.

This is deliberate rather than merely accepted. Writing the test first is not only a matter of
specification discipline — the specification is already in this document. Its real value is that
ArchUnit sees references a text search cannot. The first run bore this out: of the 25 violations
behind `compiler → datapipeline`, eleven come from `ParamType$1`, the synthetic class the compiler
emits for a `switch` over the Protobuf enum — references that appear in no source line at all.
`Simulation.programArtifacts` likewise counts three times rather than once, because the generic type
argument shows up in the field and in both accessor signatures.

**The inventory has a blind spot in the other direction, and it is not academic.** Two places must
be changed that the rule does not report:

- `ArtemisTopicResource` reads `EmbeddedBrokerProcess.DEFAULT_MAX_SIZE_BYTES`, a `static final long`
  initialised from a constant expression. The Java compiler inlines it, so the bytecode holds the
  number and no reference to the declaring class.
- `VirtualMachine` imports `ProgramArtifact` and holds it in a local variable before passing it on.
  The call it makes targets `runtime.isa.Instruction`, and a cast is not one of the dependency kinds
  ArchUnit records, so nothing is attributed to the compiler.

The two methods are therefore complementary, and neither replaces the other: the text search misses
bytecode-only references — it missed `compiler → datapipeline` until it was looked for specifically
— while the rule misses inlined constants and imports that no longer back a bytecode reference. A
green rule proves that no forbidden edge remains; it does not prove that a measure removed every
reference it set out to remove. Each measure is therefore additionally checked for leftover imports
by search.

Subject to that limit, the first run of the test is the authoritative inventory, and if it reports
anything not listed here, the measures are corrected before the work continues.

The test runs on the branch only, and JUnit reports each failure individually, so a red rule does
not hide an unrelated failing test. To keep "red is expected" verifiable rather than merely
believed, the violations that may still be reported after each step are:

| after step | still expected |
|---|---|
| Measure 1 (test in place) | `datapipeline → node`, `runtime → compiler`, `compiler → datapipeline` |
| Measure 2 (process wrapper) | `datapipeline → node` (broker only), `runtime → compiler`, `compiler → datapipeline` |
| Measure 3 (broker registry) | `runtime → compiler`, `compiler → datapipeline` |
| Measure 4 (artifact out of the runtime) | `compiler → datapipeline` |
| Measure 5 (parameter type conversion) | none |

Anything else in the output is a new finding and is investigated before proceeding.

The cost of this order is accepted knowingly, and the tabulated expectations do not remove it: while
the rule is red, the intermediate commits of the branch are not bisectable. The consequence is that
the branch is merged only once the rule is green — which is the same condition as the completion
criterion below, so it adds no separate rule to remember.

The only new build dependency is `testImplementation("com.tngtech.archunit:archunit:1.5.0")` — the
core library, deliberately not `archunit-junit5`. The JUnit 5 integration brings its own test engine
with its own tagging mechanism (`@ArchTag`), while this build selects tests through
`includeTags("unit")` and `includeTags("integration")` in separate Gradle tasks. With the core
library the rule is an ordinary `@Test` method carrying `@Tag("unit")` and needs no second tagging
system for the sake of one test.

ArchUnit is preferred over a hand-written import scan, which would miss fully qualified references,
annotations and generic parameters, and over Gradle subprojects, whose cost (build, test fixtures,
IDE setup, release packaging) is disproportionate here and which would require all cycles resolved
first in any case.

## Measure 2 — Move the pipeline process wrapper to `node`

`ServiceManagerProcess` moves from `datapipeline` to `org.evochora.node.processes.pipeline`.
Nothing about it changes except its location: it remains the adapter that gives `ServiceManager` a
process lifecycle.

The target package follows the existing convention. All four concrete processes live in a
sub-package named after the domain they wrap — `broker`, `h2`, `http` — while only `AbstractProcess`
sits directly in `processes`. `pipeline` is also the name under which the process is already
configured in `reference.conf`.

**The one real risk:** node instantiates processes reflectively from a class name in the
configuration, so the compiler does not protect this move. The class name appears in the shipped
reference configuration (`className = "org.evochora.datapipeline.ServiceManagerProcess"`), in a node
integration test and in the class's own JavaDoc example, and all must be updated together.

Two further mentions were checked and need no change, because they name the simple class name in
prose rather than the fully qualified one: a comment in `Node` and the JavaDoc of `IServiceProvider`.

Configurations outside this repository that name the old class will fail at startup with a
`ClassNotFoundException`. This is accepted as a deliberate breaking rename. No compatibility alias
is provided: a silent fallback would hide exactly the kind of misconfiguration that ought to fail
fast.

## Measure 3 — Move the broker registry to `datapipeline`

`EmbeddedBrokerProcess` is split along the seam that already runs through it. The static half — the
broker registry, in-VM URL parsing, retention state, size defaults and the whole Artemis
configuration — becomes `datapipeline.resources.broker.EmbeddedBrokerRegistry`. What remains in
`node` is the process wrapper: lifecycle, exposed service, delegation.

The target package is a sibling of the two packages that consume it, `resources/queues` and
`resources/topics`, and follows the existing subdivision of `resources`. The name states what the
class is: it does not represent a broker, it manages the running ones, and everything it holds
besides the two registries exists to operate them.

The public surface of the new class is exactly today's static methods; nothing has to be added. One
detail changes on the node side: the process's `stop()` currently reads the private
`brokerRegistry` field directly, and will use the existing `isBrokerStarted(serverId)` instead.

Consumers to repoint are the Artemis queue and topic resources, their tests and the existing broker
process test. The reference configuration is unaffected, because the process keeps both its name and
its location. The JavaDoc link in the CLI's topic cleaner points at `configureLogging()`, which is
package-private and therefore not resolvable from another package even today; it is replaced by
something meaningful or removed rather than mechanically redirected.

One consumer must be found by hand: `ArtemisTopicResource` reads
`EmbeddedBrokerProcess.DEFAULT_MAX_SIZE_BYTES`, and because that is a constant expression the
compiler inlines it, leaving no bytecode reference for the rule to report. Repointing only what the
rule names would leave that import behind with the rule still green.

This is the only measure that touches the operating path rather than observation, so the Artemis
integration tests are its acceptance criterion.

Afterwards the process wrapper imports from `datapipeline`, which is the intended direction, and the
`node` ↔ `datapipeline` cycle is gone.

## Measure 4 — Remove `ProgramArtifact` from the runtime

The runtime stops resolving label names. A procedure frame keeps only the label hash it already
carries, and resolving that hash to a name moves to the read path, where the name is actually
requested and where the artifact is already present.

The decisive property is *when* the resolution happens. Today it runs on the write path, once per
procedure call, for every organism. Afterwards it runs inside the HTTP request that asks for one
organism at one tick — `OrganismController` → `H2DatabaseReader.readOrganismDetails(tick,
organismId)` — so it is performed only for state a person is actually looking at.

This is deliberately complete removal rather than narrowing. Taking the parameter out of the ISA
signature alone would leave `Simulation` holding the artifact map, so the edge would survive and the
portability benefit — the reason for touching this at all — would not materialise.

**In `runtime`:** the artifact parameter disappears from `Instruction.execute` and from the
procedure call handler; `ProcFrame` loses its name field; `Simulation` loses the artifact map and
its accessors, and the virtual machine no longer looks an artifact up per instruction — a change the
rule does not demand, because `VirtualMachine` holds the artifact only in a local variable and
produces no bytecode reference to the compiler, yet one without which the import would survive.
Afterwards
the runtime imports nothing from the compiler.

**In the serializer:** the `setProcName` line disappears without replacement. The serializer needs
no artifact, performs no lookup, and its per-frame cost drops rather than rises.

**In the persisted format:** the `proc_name` field is removed from the `ProcFrame` message and its
number is retired with `reserved 1;`.

The reservation is discipline rather than necessity here, and it is worth stating which is which.
From this change onwards no run writes a field 1, so should the number ever be reassigned, no stored
record would carry the old meaning and the misreading that `reserved` guards against could not arise
in the first place. The reservation is set anyway, because this project has already demonstrated the
opposite discipline and what it costs: the register bank extension (2026-03-28) reassigned field
number 2 from `absolute_return_ip` to `label_hash` without reserving anything, changing that
number's wire type from length-delimited to varint. Every frame written before that point is
therefore misread against today's schema — a data error without an exception, and one that evidently
went unnoticed. `reserved 1;` costs one line and removes the question from the next person's plate
instead of requiring them to redo this analysis.

The general problem this illustrates — a run written by an incompatible build being read silently
instead of rejected — is not a per-field concern and belongs to the separate
`PERSISTED_FORMAT_VERSIONING` proposal.

**No readable run loses its names.** This follows from the schema alone, without any assumption
about which runs currently exist. A frame can only be parsed at all if it was written after the
field renumbering above, and from that point it carries `label_hash`; `label_value_to_name` has been
in the metadata since 2026-01-26, which is earlier still. Any run that can be opened therefore
carries both ingredients of the reconstruction and shows the same procedure names as today, computed
instead of stored.

Mechanically nothing breaks either: binary payloads treat the removed field as an unknown field, and
the JSON path parses with `ignoringUnknownFields`.

**In the read path:** `H2DatabaseReader.readOrganismDetails` already loads the run metadata and
already derives per-request context from it — it passes `envDimensions` into the very method that
builds the call stack. The label map travels the same way, and `OrganismStateConverter.convertProcFrame`
fills the name from `labelHash` exactly as the serializer fills it today. The organism state carries
`program_id`, which the metadata's `programs` list is keyed by, so the matching artifact is
identifiable; metadata are served from an LRU cache, so no repeated parsing occurs. There is only
one reader implementation, and no indexer or export path reads call stacks.

The map travels as an ordinary parameter, in the same shape `envDimensions` already has — no field,
no state, no new type:

```java
// H2DatabaseReader
private OrganismRuntimeView convertOrganismStateToRuntimeView(
        OrganismState orgState, int[] envDimensions, Map<Integer, String> labelValueToName)

// OrganismStateConverter
public static ProcFrameView convertProcFrame(
        ProcFrame frame, Map<Integer, String> labelValueToName)
```

`readOrganismDetails` selects the map from the metadata it has already loaded, keyed by
`orgState.getProgramId()`.

`convertProcFrame` gains a parameter, so every caller has to supply the map — and one of them cannot.
`OrganismStateConverter.decodeRuntimeState` builds call stacks the same way but has no metadata and
no parameter to receive them; it is also called from nowhere in `src/main` or `src/test`. **It is
deleted rather than adapted.** Passing `null` there would be the path of least resistance and would
produce a public method that silently returns unresolved names — breaking the guarantee this measure
establishes, in code nobody exercises. This is the same case as the unused `ParamInfo` conversion
methods in measure 5, and it is resolved the same way.

**The front-end is not touched, and that is the point.** `ProcFrameView` keeps `procName`, so the
HTTP response shape is unchanged and no consumer of the API has to adapt. This matters beyond
convenience, because the name is not only displayed there. `OrganismStateView.formatCallStack` uses
the *presence* of names as a mode switch between two entirely different call-stack renderings, and
uses the name again as an uppercase key into `procNameToParamNames` for the REF/VAL parameter
display; `OrganismSourceView.detectUnknownProcedure` uses it as a key as well, to report that an
organism is executing a procedure the compiled artifact does not contain — a diagnosis of mutated
code, not a caption. Resolving server-side leaves all three untouched.

For the same reason the resolution must yield an empty name for an unknown hash and never a
placeholder string: `procName` is filled from `labelValueToName` today, so resolving from the same
map preserves the key exactly, and a substituted placeholder would flip the mode switch and break
the parameter lookup.

**Simplification of the engine's program bookkeeping.** `SimulationEngine` keeps a record
`ProgramInfo(programPath, programId, artifact)` in a path-keyed map. `programPath` is read nowhere,
in `src/main` or `src/test`, and `programId` is available from the artifact, so the record carries
one field of information. The map that survives initialisation therefore becomes the id-keyed
`Map<String, ProgramArtifact>` that already exists as `compiledPrograms` — the same map that is
handed to `Simulation` today and that this measure stops handing over.

The path key stays where it is genuinely needed and nowhere else: inside initialisation, for the
compile loop that avoids compiling one program twice and for placing organisms, which the
configuration addresses by program path. That structure is local to initialisation and dies with
it. `ProgramInfo`, the engine field and the corresponding field in the initialised-state record all
disappear, as does the artificial wrapper the resume path builds today to satisfy the path-keyed
shape.

**A missing name is a correct result, not a fallback — and it is the common case.** `program_id`
identifies the ancestral program, not the mutated code of a descendant: an organism inherits the id
from its parent while its code diverges. The artifact is therefore always found, but a procedure
call may target a hash the original program never contained. As a population evolves, frames without
a name become the rule rather than the exception.

There is no name to report in those cases, and reporting none is the truthful observation — which is
why the resolution yields an empty name and the front-end continues to render such frames the way it
does today. This is stated explicitly because the codebase otherwise rejects silent fallbacks, and
without this note a later reader could mistake the normal outcome of evolution for a defect and
"fix" it into an error.

The second case the earlier draft of this proposal worried about — no artifact at all for a program
id — no longer needs a guard of its own. It can only occur while serving one HTTP request for one
organism, it is confined to that response, and it is indistinguishable in effect from the case
above. The fail-fast check at engine initialisation that would have guarded a per-organism lookup on
the write path is therefore not needed.

**`label_hash` gains a second role.** The field exists today for the persistent register state
lookup, as its own comment in the proto states. After this measure it is additionally the input for
name resolution. Both uses must be kept in mind by anyone who changes it later; this is recorded
because the proto comment currently names only the first.

**Behavioural changes, named.** Two, neither of them affecting the simulation:

- The memory estimate for compiled programs counts programs by id instead of by path. Because
  `programId` is a content hash, two configuration entries pointing at identical programs were
  counted twice while only one artifact was ever held in memory. The estimate becomes correct.
- The persisted `proc_name` field disappears, as described above, without loss of displayed names
  for any run that is readable at all.

Name resolution moves from every procedure call to the HTTP request that asks for a specific
organism. Since the name never influenced execution — provable from the guard against a missing
artifact — the simulation is unchanged, and serialization remains a pure observer.

**Expected side effect, not a claim.** Removing a map lookup from the call path and a string
reference from every stack frame should reduce hot-path work and per-organism heap. This has **not**
been measured and is not a justification for the measure; it is recorded so a benchmark run can
confirm or refute it.

**Test impact — nine files, smaller than it looks.** No test calls `execute` directly; the VM tests
drive instructions through `sim.tick()`. Of the 22 test files that mention `ProgramArtifact`, 21 are
compiler tests that this measure does not touch. What remains: five files construct an
`Organism.ProcFrame` and drop its first argument, three build a Protobuf frame and drop
`setProcName`, and `RuntimeIntegrationTest` loses two calls to `setProgramArtifacts`.

One test is removed rather than adapted. `procedureCopyOut_worksWithoutProgramArtifact` exists, by
its own JavaDoc, to prove that the generated machine code needs no artifact at runtime — an
assertion that has no counter-state left once the runtime holds no artifacts at all. Its remaining
substance is covered better by its neighbour `procedureWithParametersAddsValuesAtRuntime`, which
exercises the same REF copy-out, already runs without an artifact because it never sets one, and
checks two parameters instead of one.

The new resolution is covered by tests of the read path in the existing Java suite — a known hash
resolves, an unknown hash yields an empty name, a run without the label map yields empty names
throughout. Nothing moves into the front-end, which has no test coverage of any kind; keeping the
change on the Java side is a deliberate part of the design rather than a coincidence.

## Measure 5 — Remove the wire format from the compiler API

`ParamType.toProtobuf()` and `ParamType.fromProtobuf(...)` move to their callers, both of which are
already in `datapipeline`: the first into `SimulationEngine.convertProgramArtifact`, the second into
`SimulationRestorer.convertProtoProgramArtifact`. That is where the surrounding `ParamInfo` is built
field by field today, so the conversion of the record ends up in one place instead of two.

`ParamInfo.fromProtobuf` and `ParamInfo.toProtobufBuilder` are deleted. They are called by nothing,
in `src/main` or `src/test`; they are the encapsulation that was built and then not used.

Afterwards `compiler` references nothing outside `runtime`, and the `compiler` ↔ `datapipeline`
cycle is gone. This is the last violation, so the rule from measure 1 turns green here.

## Sequence

The proposal is implemented as one unit. Within it:

1. **Measure 1** — write the rule in its final form, run it once as the authoritative inventory, and
   record the graph and the process-wrapper principle in `AGENTS.md`. The test is red from here
   on, against the expectations tabulated above.
2. **Measure 2** — move the pipeline process wrapper. Smallest change; the reflective binding is the
   only risk.
3. **Measure 3** — extract the broker registry. Operating path; Artemis integration tests decide.
4. **Measure 4** — remove the compiler artifact from the runtime. Largest blast radius; touches the
   hot path, the persisted state shape and the read path.
5. **Measure 5** — remove the wire format from the compiler API. Small, and the step that turns the
   rule green.

Each step is implemented and verified on its own, and the rule from step 1 turns green only when the
last one lands. That transition is the completion criterion for the proposal.

## Optional packages

The three packages below are not part of the measures. They stand outside the sequence and outside
the completion criterion: the rule from measure 1 turns green without any of them. Each is described
here as a single agreed solution so that the decision — implement here, split into its own proposal,
or leave as an issue — can be taken on a concrete description rather than a sketch.

### Package A — One place for domain-to-wire conversion

Conversion between domain types and Protobuf happens on two axes, and each axis is split across two
packages:

| Axis | Forward | Reverse |
|---|---|---|
| `ProgramArtifact` ↔ proto | `SimulationEngine`, ~120 lines | `SimulationRestorer`, ~165 lines |
| `Organism` ↔ proto | `OrganismStateSerializer` | `SimulationRestorer` |

The only thing holding each pair together is a comment in `SimulationRestorer`: *"This is the reverse
of SimulationEngine.convertProgramArtifact()."* Someone knew the halves belong together and could
only write it down as prose. The `Vector` conversion is outright duplicated between
`SimulationEngine` and `OrganismStateSerializer`.

The package introduces `datapipeline.utils.codec` containing `ProgramArtifactCodec` and
`OrganismStateCodec`, each holding both directions, plus a package-private `CommonCodecs` for the
shared `Vector`, `SourceInfo` and `TokenInfo` conversions. This follows the existing `DeltaCodec`
pattern — `utils/<topic>/`, a class named `Codec`, encoder and decoder side by side — which is also
the reason domain knowledge under `utils` is not out of place here.

The motivation is correctness, not tidiness: the two directions of one schema must match, and a new
field needs both. Split across classes in different packages, one can be forgotten, and the mistake
surfaces at resume time — late and expensive. Side by side it is obvious.

Both axes are included. Taking only the artifact axis would leave the duplicated `Vector`
conversion in place and miss the point. The Proto→DTO conversion of the visualizer is *not*
included: it crosses no domain boundary, translating one `datapipeline` representation into another.

If implemented here, this package comes **after** measures 4 and 5, which both modify exactly the
code it relocates.

Package and class names are settled above; the remaining detail — the exact signatures of the two
codecs and what `CommonCodecs` holds — is decided once it is clear that this package is implemented
at all, and before any of it is written.

### Package B — Fail fast on a missing embedded broker ([#107](https://github.com/evochora/evochora/issues/107))

`getServer(serverId)` returns `null` in two entirely different situations, and the code distinguishes
them nowhere: for an external broker URL (`serverId == -1`) no embedded server can exist and its
absence is correct, while for `vm://n` a missing server is always a misconfiguration or a wrong
startup order.

`ArtemisQueueResource` reacts to that single `null` in six places with six different fallbacks, from
a debug line to a substituted metric. Issue #107 describes four of them; the analysis for this
proposal added three findings, which have been appended to the issue:

- two metric methods return `0` rather than reporting that the value is not measurable, making an
  empty queue indistinguishable from an unmeasurable one in recorded data;
- four `catch (Exception e)` blocks discard the exception entirely, with no logging at all;
- `configureQueueAddressSettings` and `isQueueAtCapacity` form a coupled defect: the capacity check
  proceeds because "BLOCK policy handles it", and the BLOCK policy is exactly what the other method
  skips in the same situation, so the queue runs with neither limit nor check.

The sibling class already applies the intended rule with the reasoning written into the code —
`ArtemisTopicResource.queueExistsInBroker`: *"FAIL FAST … We must NOT guess."* The inconsistency sits
between two classes in the same family.

The package introduces the missing distinction, fails at resource construction for a `vm://` URL
without a running broker, reports non-measurable metrics as such, and stops discarding exceptions.
Its acceptance criterion is the Artemis integration tests, as for measure 3.

Measure 3 rewrites exactly these call sites, so doing this in the same pass avoids touching the code
twice.

### Package C — Fail fast on resource setup ([#106](https://github.com/evochora/evochora/issues/106))

`ServiceManager` continues after a resource or an initializer fails to instantiate. Issue #106
describes two such sites; a third was found for this proposal and appended to the issue: the scan
for `init` blocks fails at `log.debug`, which leaves no trace in normal operation — a malformed
`init` block means the initializer never runs and nothing reports it.

Unlike package B, this one has **no** overlap with any measure here: measure 2 moves
`ServiceManagerProcess`, not `ServiceManager`. It is recorded for completeness because it is the
same category of defect, found in the same analysis.

## Out of scope

- Intra-`runtime` layering, and the internal structure of `datapipeline` beyond what package A
  covers.
- Gradle subprojects or JPMS modules. This proposal removes the obstacles, so the question can be
  taken up later on its own merits; it is not decided here.
- Moving the Protobuf contracts out of the `datapipeline` namespace, as discussed under measure 1.

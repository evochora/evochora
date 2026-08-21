# Package Dependency Rules

**Status: PROBLEM VERIFIED — solution approaches listed for discussion, none agreed.**

## Problem

The codebase has no stated rule for which top-level packages may depend on which, and nothing
enforces one. Today the only check is a reviewer's judgement: the architecture review of the
deterministic-execution branch (2026-08-20) flagged a layering inversion (`runtime.model` and
`runtime.internal.services` depending on `runtime.TickWorkerPool`) from its own reasoning, not
from a guideline — `.agents/architecture-guidelines.md` and `AGENTS.md` contain no dependency rule
between `runtime`, `compiler`, `datapipeline`, `node` and `cli`.

Measured on `feature/deterministic-execution` @ `5bad7f49` (import statements between top-level
packages under `src/main/java/org/evochora`):

| From → to | Imports | Notes |
|---|---|---|
| `cli` → `datapipeline` (56), `runtime` (12), `compiler` (6), `node` (1) | | entry point; expected to see everything |
| `node` → `datapipeline` (42), `runtime` (6) | | |
| `datapipeline` → `runtime` (39), `compiler` (14), **`node` (4)** | | `node` → `datapipeline` → `node` is a **cycle** (`AbstractProcess`, `EmbeddedBrokerProcess`, `IServiceProvider`) |
| `compiler` → `runtime` (34) | | `EnvironmentProperties`, `RegisterBank`, `Instruction`, `Molecule`, … — the ISA is the compiler's target, so most of this is intended; `CompilerRunner` importing `Organism`/`Environment` is not obviously so |
| `runtime` → `compiler` (15) | | a single type, `compiler.api.ProgramArtifact`, used by `VirtualMachine`, `ProcedureCallHandler`, `NopInstruction`, … — `runtime` ↔ `compiler` is a **cycle** |

Inside `runtime`, `model` depends upward on `Simulation`, `Config`, `ParallelWave`, `isa`, `label`;
there is no declared intra-runtime layering either.

Consequences: the dependency graph can only degrade unnoticed; a future split into modules
(Gradle subprojects, JPMS) is blocked by the two cycles; reviewers re-derive the intended
structure on every PR.

## Required outcome

1. A written, agreed dependency graph for the top-level packages (and, if wanted, for the layers
   inside `runtime`), recorded where reviewers and agents read it (`AGENTS.md` /
   `.agents/architecture-guidelines.md`).
2. An automated check that fails the build on a violation, so the rule does not depend on a
   reviewer noticing.
3. A decision for each existing violation: fix it, or whitelist it explicitly with a reason.

## Approaches to discuss (not agreed)

**A. Rule as data, enforced by an ArchUnit test.** A `@Tag("unit")` test declares the allowed
edges (`slices().matching("org.evochora.(*)..").should().beFreeOfCycles()` plus explicit
`noClasses().that().resideInAPackage("..runtime..").should().dependOnClassesThat()...`). Pros:
standard tool, readable rules, precise failure messages, runs with the normal suite. Cons: a new
test dependency; cycles that exist today must be fixed or frozen first (ArchUnit's
`FreezingArchRule` records the current violations and fails only on new ones).

**B. Gradle subprojects.** Split `runtime`, `compiler`, `datapipeline`, `node`, `cli` into modules
with declared `implementation(project(...))` edges; the compiler then enforces the graph. Pros:
strongest enforcement, no extra tool, forces the cycles to be resolved. Cons: largest change
(build, test fixtures, IDE setup, release packaging); must resolve both cycles up front.

**C. Lightweight import scan in the test suite.** A small test walks `src/main/java`, parses
`import` lines and checks them against an allow-list. Pros: no dependency, trivial to read.
Cons: reimplements a fraction of ArchUnit, misses fully-qualified references without imports,
easy to bypass unintentionally.

Whichever approach: the two cycles need their own decisions. Candidates, to be discussed, not
decided:
- `runtime` ↔ `compiler`: move `ProgramArtifact` (and whatever the runtime needs from it) into a
  package both may depend on — e.g. a `program`/`artifact` package below both, or `runtime` owning
  the artifact contract the compiler produces.
- `node` ↔ `datapipeline`: `datapipeline` reaching into `node.processes`/`node.spi` suggests the
  process abstraction belongs to a lower package, or `datapipeline`'s broker code belongs to `node`.

## Out of scope

Changing behaviour. This proposal is about structure and its enforcement only.

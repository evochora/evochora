# Proposals

Design documents for changes that are agreed in principle but not yet implemented.
Once a proposal is fully implemented it moves to `docs/outdated/proposals/accomplished/`;
if it is dropped, it moves to `docs/outdated/proposals/declined_or_outdated/`.

A proposal holds agreed solutions only — no alternatives, no open choices; every decision, down to
a package name, is made with the maintainer before it is written down, never deferred to the
implementation plan.

Documents under [`ideas/`](ideas/) are not proposals — see below.

## Planned work

Rows are in the order the work is taken up. Issues are listed alongside proposals so that one
table carries the whole order.

| Document / Issue | Status | Summary |
|---|---|---|
| [PERSISTED_FORMAT_VERSIONING](PERSISTED_FORMAT_VERSIONING.md) | TO BE REVIEWED | Storage batches, run database and run metadata carry no format version, so data written by an incompatible build is read silently or fails without naming the cause; one version constant plus fail-fast reads |
| [DEPENDENCY_UPDATE](DEPENDENCY_UPDATE.md) | TO BE REVIEWED | 24 of 32 dependencies behind, six by a major version; removal of the unused JLine pair, three build hygiene fixes, and a staged update procedure derived from what the test suite can and cannot verify |

The two dependency documents are related: DEPENDENCY_UPDATE establishes which formats a compatibility fixture may
legitimately cover, and delegates the formats this codebase owns to PERSISTED_FORMAT_VERSIONING.

## Compiler enhancements

Two compiler proposals remain open. CONDITIONAL_COMPILATION (`.DEFINE` flags, `.IFDEF` blocks, the
`.END*` naming of the block directives) and QUALIFIED_NAMES (modules and procedures as levels, a name
reached by its path, one visibility rule, the path as the identity of a name) are accomplished and
live under [`docs/outdated/proposals/accomplished/`](../outdated/proposals/accomplished/).

| # | Document | Status | Summary |
|---|---|---|---|
| 1 | [BLOCK_MECHANISM](compiler-enhancements/BLOCK_MECHANISM.md) | PROPOSED | The structure of a block is read by the core before its handler, in one reader for the preprocessor and the parser; a broken block is reported and skipped; a label takes no statement; `.PROC` stands only at the module level |
| 2 | [CONTROL_FLOW_DIRECTIVES](compiler-enhancements/CONTROL_FLOW_DIRECTIVES.WIP.md) | **WORK IN PROGRESS** | Control blocks; rewritten on top of [QUALIFIED_NAMES](../outdated/proposals/accomplished/QUALIFIED_NAMES.md) |

Dependencies:

- **2 builds on 1**: `.CONTROL`/`.CASE`/`.ENDCONTROL` is a block kind of the parser's discipline.
- **2 builds on the `.END*` naming convention** that CONDITIONAL_COMPILATION established (`.ENDIF`).
- **[CONDITIONAL_JUMPS](../outdated/proposals/accomplished/CONDITIONAL_JUMPS.md) is optional for 2**, and accomplished: control flow directives work with conditional skips;
  with conditional jumps, `.IF` over a jump compiles to the negated jump.

## Ideas

[`ideas/`](ideas/) holds collected ideas and backlogs that have **not** been decided. They are not
specifications and carry no commitment to implement.

| Document | Summary |
|---|---|
| [MUTATIONAL_ROBUSTNESS_ASSAY](ideas/MUTATIONAL_ROBUSTNESS_ASSAY.md) | CLI instrument that classifies all single-mutation variants of a genome (lethal/sterile/impaired/neutral/improved) to measure fitness-landscape ruggedness before/after changes |
| [GRADED_CHEMISTRY_ADDITIONS](ideas/GRADED_CHEMISTRY_ADDITIONS.md) | Five additions to the reaction-network idea of SCIENTIFIC_OVERVIEW §4.6: continuous yield, compositionality criterion, generative schema, spontaneous reactions, stagnation risk |
| [BIT_CHEMISTRY](ideas/BIT_CHEMISTRY.md) | Concrete candidate for the §4.6 reaction system: bound energy as substrate bits, one XOR/AND rule with continuous yield, grid-only reactions with a closed energy accounting — and a reachability analysis showing it yields slopes for parameters, not new behaviour |
| [CONTINUOUS_REGULATION](ideas/CONTINUOUS_REGULATION.md) | EMIT/SENS: organism-internal signal concentrations with Hamming-weighted graded sensing and per-tick decay — behavior depends gradually on internal state instead of only hard branches |
| [DUMB_VARIATION_PACKAGE](ideas/DUMB_VARIATION_PACKAGE.md) | Prerequisite analysis for §4.3's open mutation mechanisms: degenerate opcode encoding + operand totality first, only then copy-error/cosmic-ray variation — as one package, measured by the robustness assay |
| [REPLICATION_PRIMITIVES](ideas/REPLICATION_PRIMITIVES.md) | Identity decision: replication as physics (DIVIDE), status quo (~1000-instruction program), or polymerase-style copy primitives in between — option spectrum documented neutrally |
| [EVOCHORA_X](ideas/EVOCHORA_X.md) | Documented non-decision with literature basis: why a continuous substrate (CTRNN/ODE, strand displacement, Flow-Lenia-class) is a separate research track, not Evochora 2.0 |
| [LOCAL_STATE](ideas/LOCAL_STATE.md) | `.STATE`/`.LOAD`/`.STORE` directives for grid-backed module state, replacing manual `.ORG`+`.PLACE`+`STATIC_LOAD` macros |
| [DATA_ACCESS_IMPROVEMENTS](ideas/DATA_ACCESS_IMPROVEMENTS.md) | Backlog for analysis tooling: `death_tick` column, lifecycle/Muller/lineage analytics plugins, read-only SQL access |
| [ALLOCATION_FREE_CELL_ACCESS](ideas/ALLOCATION_FREE_CELL_ACCESS.md) | Handover from the tiled-grid work: the coordinate-based cell accessors allocate on every instruction (25–29 % of engine time in the profile); call sites, constraints, measurement method and the open design choices for making them allocation-free |
| [EVOLUTION_VISUALIZATION](ideas/EVOLUTION_VISUALIZATION.md) | Catalogue of ten visualisation ideas for evolutionary dynamics, classified by target view and data availability |
| [OPEN_COMPILER_BACKEND](ideas/OPEN_COMPILER_BACKEND.md) | Compiler backend with feature-defined IR item kinds and per-phase item-handler registries; due when the first feature needs a kind beyond instruction, label and directive |

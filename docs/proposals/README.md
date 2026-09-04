# Proposals

Design documents for changes that are agreed in principle but not yet implemented.
Once a proposal is fully implemented it moves to `docs/outdated/proposals/accomplished/`;
if it is dropped, it moves to `docs/outdated/proposals/delined_or_outdated/`.

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

Four related compiler proposals. They share a dependency chain, listed here in the order that
resolves it.

| # | Document | Status | Summary |
|---|---|---|---|
| 1 | [RELATIVE_ORG_DIR](compiler-enhancements/RELATIVE_ORG_DIR.md) | TO BE REVIEWED | `~` prefix for relative `.ORG` offsets and `.DIR` rotation; bounds checking for non-toroidal grids. Step 1 is an independent bug fix (silently discarded out-of-bounds placement) |
| 2 | [CONDITIONAL_COMPILATION](compiler-enhancements/CONDITIONAL_COMPILATION.md) | TO BE REVIEWED | `.IFDEF` / `.ELSEDEF` / `.ENDDEF` preprocessor conditionals; renames `.DEFINE` → `.CONST` and `.ENDP`/`.ENDM`/`.ENDR` → `.ENDPROC`/`.ENDMACRO`/`.ENDREPEAT` |
| 3 | [CONTROL_FLOW_DIRECTIVES](compiler-enhancements/CONTROL_FLOW_DIRECTIVES.WIP.md) | **WORK IN PROGRESS** | `.IF`/`.ELSEIF`/`.ELSE`/`.ENDIF` specified; `.WHILE`/`.FOR`/`.BREAK`/`.CONTINUE` still to be written |
| 4 | [CONDITIONAL_BRANCH_ISA](compiler-enhancements/CONDITIONAL_BRANCH_ISA.md) | TO BE REVIEWED | Branch variants (BFI, BNR, BLE…) for all 18 conditional operations — condition test and jump in one instruction |

Dependencies:

- **3 requires 2** for the `.END*` naming convention (`.ENDIF`).
- **4 is optional for 3**: control flow directives work with skip-next instructions; branch instructions
  only improve code density of the generated sequences.
- **1 is independent** of the other three.

## Ideas

[`ideas/`](ideas/) holds collected ideas and backlogs that have **not** been decided. They are not
specifications and carry no commitment to implement.

| Document | Summary |
|---|---|
| [MUTATIONAL_ROBUSTNESS_ASSAY](ideas/MUTATIONAL_ROBUSTNESS_ASSAY.md) | CLI instrument that classifies all single-mutation variants of a genome (lethal/sterile/impaired/neutral/improved) to measure fitness-landscape ruggedness before/after changes |
| [FAILURE_CAUSE_ANALYTICS](ideas/FAILURE_CAUSE_ANALYTICS.md) | Aggregate instruction-failure *causes* (not just the existing failure rate) as a per-tick histogram — the live counterpart of the robustness assay |
| [GRADED_CHEMISTRY_ADDITIONS](ideas/GRADED_CHEMISTRY_ADDITIONS.md) | Five additions to the reaction-network idea of SCIENTIFIC_OVERVIEW §4.6: continuous yield, compositionality criterion, generative schema, spontaneous reactions, stagnation risk |
| [CONTINUOUS_REGULATION](ideas/CONTINUOUS_REGULATION.md) | EMIT/SENS: organism-internal signal concentrations with Hamming-weighted graded sensing and per-tick decay — behavior depends gradually on internal state instead of only hard branches |
| [DUMB_VARIATION_PACKAGE](ideas/DUMB_VARIATION_PACKAGE.md) | Prerequisite analysis for §4.3's open mutation mechanisms: degenerate opcode encoding + operand totality first, only then copy-error/cosmic-ray variation — as one package, measured by the robustness assay |
| [REPLICATION_PRIMITIVES](ideas/REPLICATION_PRIMITIVES.md) | Identity decision: replication as physics (DIVIDE), status quo (~1000-instruction program), or polymerase-style copy primitives in between — option spectrum documented neutrally |
| [EVOCHORA_X](ideas/EVOCHORA_X.md) | Documented non-decision with literature basis: why a continuous substrate (CTRNN/ODE, strand displacement, Flow-Lenia-class) is a separate research track, not Evochora 2.0 |
| [LOCAL_STATE](ideas/LOCAL_STATE.md) | `.STATE`/`.LOAD`/`.STORE` directives for grid-backed module state, replacing manual `.ORG`+`.PLACE`+`STATIC_LOAD` macros |
| [DATA_ACCESS_IMPROVEMENTS](ideas/DATA_ACCESS_IMPROVEMENTS.md) | Backlog for analysis tooling: `death_tick` column, lifecycle/Muller/lineage analytics plugins, read-only SQL access |
| [EVOLUTION_VISUALIZATION](ideas/EVOLUTION_VISUALIZATION.md) | Catalogue of ten visualisation ideas for evolutionary dynamics, classified by target view and data availability |
| [OPEN_COMPILER_BACKEND](ideas/OPEN_COMPILER_BACKEND.md) | Compiler backend with feature-defined IR item kinds and per-phase item-handler registries; due when the first feature needs a kind beyond instruction, label and directive |

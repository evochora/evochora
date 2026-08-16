# Proposals

Design documents for changes that are agreed in principle but not yet implemented.
Once a proposal is fully implemented it moves to `docs/outdated/proposals/accomplished/`;
if it is dropped, it moves to `docs/outdated/proposals/delined_or_outdated/`.

Documents under [`ideas/`](ideas/) are not proposals — see below.

## Open proposals

| Document | Status | Summary |
|---|---|---|
| [DATA_MUTATION_SIGN_FIX](DATA_MUTATION_SIGN_FIX.md) | TO BE REVIEWED | Scale-proportional DATA mutation is not smooth for negative values; verified defect in `GeneSubstitutionPlugin` with a test-first fix plan |

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
| [LOCAL_STATE](ideas/LOCAL_STATE.md) | `.STATE`/`.LOAD`/`.STORE` directives for grid-backed module state, replacing manual `.ORG`+`.PLACE`+`STATIC_LOAD` macros |
| [DATA_ACCESS_IMPROVEMENTS](ideas/DATA_ACCESS_IMPROVEMENTS.md) | Backlog for analysis tooling: `death_tick` column, lifecycle/Muller/lineage analytics plugins, read-only SQL access |
| [EVOLUTION_VISUALIZATION](ideas/EVOLUTION_VISUALIZATION.md) | Catalogue of ten visualisation ideas for evolutionary dynamics, classified by target view and data availability |

# Ideas

Collected ideas and backlogs that have **not** been decided. They are not specifications and carry
no commitment to implement. Proposals, the agreed designs, live one directory up and move to
`docs/outdated/proposals/accomplished/` once implemented.

| Document | Summary |
|---|---|
| [MUTATIONAL_ROBUSTNESS_ASSAY](MUTATIONAL_ROBUSTNESS_ASSAY.md) | CLI instrument that classifies all single-mutation variants of a genome (lethal/sterile/impaired/neutral/improved) to measure fitness-landscape ruggedness before/after changes |
| [GRADED_CHEMISTRY_ADDITIONS](GRADED_CHEMISTRY_ADDITIONS.md) | Five additions to the reaction-network idea of SCIENTIFIC_OVERVIEW §4.6: continuous yield, compositionality criterion, generative schema, spontaneous reactions, stagnation risk |
| [BIT_CHEMISTRY](BIT_CHEMISTRY.md) | Concrete candidate for the §4.6 reaction system: bound energy as substrate bits, one XOR/AND rule with continuous yield, grid-only reactions with a closed energy accounting — and a reachability analysis showing it yields slopes for parameters, not new behaviour |
| [CONTINUOUS_REGULATION](CONTINUOUS_REGULATION.md) | EMIT/SENS: organism-internal signal concentrations with Hamming-weighted graded sensing and per-tick decay — behavior depends gradually on internal state instead of only hard branches |
| [DUMB_VARIATION_PACKAGE](DUMB_VARIATION_PACKAGE.md) | Prerequisite analysis for §4.3's open mutation mechanisms: degenerate opcode encoding + operand totality first, only then copy-error/cosmic-ray variation — as one package, measured by the robustness assay |
| [REPLICATION_PRIMITIVES](REPLICATION_PRIMITIVES.md) | Identity decision: replication as physics (DIVIDE), status quo (~1000-instruction program), or polymerase-style copy primitives in between — option spectrum documented neutrally |
| [EVOCHORA_X](EVOCHORA_X.md) | Documented non-decision with literature basis: why a continuous substrate (CTRNN/ODE, strand displacement, Flow-Lenia-class) is a separate research track, not Evochora 2.0 |
| [LOCAL_STATE](LOCAL_STATE.md) | `.STATE`/`.LOAD`/`.STORE` directives for grid-backed module state, replacing manual `.ORG`+`.PLACE`+`STATIC_LOAD` macros |
| [DATA_ACCESS_IMPROVEMENTS](DATA_ACCESS_IMPROVEMENTS.md) | Backlog for analysis tooling: `death_tick` column, lifecycle/Muller/lineage analytics plugins, read-only SQL access |
| [ALLOCATION_FREE_CELL_ACCESS](ALLOCATION_FREE_CELL_ACCESS.md) | Handover from the tiled-grid work: the coordinate-based cell accessors allocate on every instruction (25–29 % of engine time in the profile); call sites, constraints, measurement method and the open design choices for making them allocation-free |
| [EVOLUTION_VISUALIZATION](EVOLUTION_VISUALIZATION.md) | Catalogue of ten visualisation ideas for evolutionary dynamics, classified by target view and data availability |
| [OPEN_COMPILER_BACKEND](OPEN_COMPILER_BACKEND.md) | Compiler backend with feature-defined IR item kinds and per-phase item-handler registries; due when the first feature needs a kind beyond instruction, label and directive |

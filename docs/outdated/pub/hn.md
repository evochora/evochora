Show HN: Evochora – A Laboratory for Embodied Artificial Life Research

Hi HN, author here.

I am a 48-year-old ex-computer scientist returning to coding after a 20-year hiatus. I built Evochora because I wanted to tackle a specific problem in Artificial Life: Evolutionary Stagnation, analogous to the "boring billion" in Earth's evolution.

Scientific ALife simulators often hit a complexity ceiling. My hypothesis is that overcoming this requires flexible experimentation with "digital physics" rather than hard-coded rules. Evochora is designed as a modular platform to test these variables.

Evochora is a modular, extensible platform designed for scientific experimentation. The core philosophy: test hypotheses about "digital physics" without modifying the engine. Plugin systems enable this throughout:

    *VM & Compiler*: Turing-complete VM with configurable registers/stacks. Multi-pass compiler for EvoASM (Lexer → Parser → Semantic Analysis → IR → Layout → Linker → Emitter). Both extensible via plugins—add instructions, constraints, optimization passes, or syntax extension without touching core code.

    *Embodied Simulation*: Organisms are embodied agents in an n-dimensional grid, interacting via physical Instruction Pointers and Data Pointers not scripts in a sandbox.

    *Distributed Pipeline*: Hot path (flattened int32 arrays for cache locality) + cold path (queues, scalable persistence/indexing). Debugging and Analytics frontend with plugin-based custom metrics.

*Current Status & Research Roadmap*:

A primordial replicator (written in EvoASM) is functional, but current simulations ultimately result in genome degradation (expected without additional constraints). The platform can now be used to test specific mechanisms to overcome this:

    Genomic Stability: Testing fuzzy jump targets (SignalGP style) vs. absolute addressing.

    Thermodynamics: Implementing entropy/waste heat constraints for every instruction execution.

    Digital Eukaryogenesis: Testing organism-internal multi-threading (FORK opcode) to separate replication from "metabolic" background tasks.

    Signaling: Implementing fuzzy signaling between execution contexts.

The project is fully Open Source (MIT License). I'm looking for critical feedback on the architecture and contributors. If you're a systems engineer or researcher interested in designing the "digital physics" (thermodynamics, metabolic constraints) required to push this system beyond stagnation, I'd love to collaborate.

Tech Stack: Java/Gradle. Full disclosure: AI was my primary coding partner (after 20 years away from coding), but all architectural and design decisions were mine. Happy to discuss the architecture here; AI workflow details on Discord if interested.

Repo: https://github.com/evochora/evochora
Demo: http://evochora.org (live simulation + video)
Discord: https://discord.gg/SXSDvfcm
Whitepaper (draft!): https://github.com/evochora/evochora/blob/main/docs/SCIENTIFIC_OVERVIEW.md

Happy to discuss any tech details or the scientific approach!
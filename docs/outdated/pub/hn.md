Show HN: Evochora – A Laboratory for Embodied Artificial Life Research

Hi HN, author here.

I am a 48-year-old ex-computer scientist returning to coding after a 20-year hiatus. I built Evochora because I wanted to tackle a specific problem in Artificial Life: Evolutionary Stagnation, analogous to the "boring billion" in Earth's evolution.

Scientific ALife simulators often hit a complexity ceiling. My hypothesis is that overcoming this requires a modular platform for flexible experimentation with "digital physics" rather than hard-coded rules. 

Evochora was designed to be this platform. Its core philosophy is to test these hypotheses without modifying the core engine, which is enabled by plugin systems throughout:

    *VM & Compiler*: Turing-complete VM with configurable registers/stacks. Multi-pass compiler for EvoASM (Lexer → Parser → Semantic Analysis → IR → Layout → Linker → Emitter). Both extensible via plugins—add instructions, constraints, optimization passes, or syntax extension without touching core code.

    *Embodied Simulation*: Organisms are embodied agents in an n-dimensional grid, interacting via physical Instruction Pointers and Data Pointers not scripts in a sandbox.

    *Distributed Pipeline*: Hot path (flattened int32 arrays for cache locality) + cold path (queues, scalable persistence/indexing). Debugging and Analytics frontend with plugin-based custom metrics.

*Current Status & Next Steps*:
A primordial replicator (written in EvoASM) is functional. I was able to overcome the expected initial genome degeneration (organisms corrupting neighbors) by enforcing configurable thermodynamic policies. Now populations are much more stable, which allows to shift the focus from survival to evolvability. Next things I want to address:

* Mutation Model: I want to build a plugin system that allows for experimentation with different mutation models (from simple bitflip to complex genome rearrangements)

* Fuzzy Addressing: I intend to investigate fuzzy jump targets (inspired by SignalGP) to decouple instruction targets from physical memory addresses, potentially improving genomic resilience.

* Signaling & Concurrency: I aim to explore how organisms can utilize the FORK opcode to spawn additional execution contexts internally and use internal signaling, which could be a kind of digital eukaryogenesis.

*The Ask*:
The project is fully Open Source (MIT License). I'm looking for contributors and critical feedback on the architecture. With the thermodynamic baseline now established, I specifically invite researchers and systems engineers to collaborate on the next frontiers: designing the mutation models and signaling protocols required to investigate emergent complexity

Tech Stack: Java/Gradle. Full disclosure: AI was my primary coding partner (after 20 years away from coding), but all architectural and design decisions were mine. Happy to discuss the architecture here; AI workflow details on Discord if interested.

Repo: https://github.com/evochora/evochora
Demo: http://evochora.org (live simulation + video)
Discord: https://discord.gg/SXSDvfcm
Whitepaper (draft!): https://github.com/evochora/evochora/blob/main/docs/SCIENTIFIC_OVERVIEW.md

Happy to discuss any tech details or the scientific approach!
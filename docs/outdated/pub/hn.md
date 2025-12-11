Show HN: Evochora – A Laboratory for Embodied Artificial Life Research

Hi HN, author here.

I am a 48-year-old ex-computer scientist returning to coding after a 20-year hiatus. I built Evochora because I wanted to tackle a specific problem in Artificial Life: Evolutionary Stagnation.

Scientific ALife simulators often hit a complexity ceiling. My hypothesis is that overcoming this requires flexible experimentation with "digital physics" rather than hard-coded rules. Evochora is designed as a modular platform to test these variables.

System Architecture:

    Embodied Agents: Organisms are not scripts in a sandbox but embodied agents in a shared n-dimensional grid. They interact with the molecules in the environment via a physical Instruction Pointer (IP) and multiple Data Pointers (DP).

    Configurable VM: The Virtual Machine is Turing-complete and executes low-level instructions (EvoASM). The architecture is strictly modular:

        Register counts, stack depths, and memory layout are configurable.

        Plugin System: The instruction set and organism properties can be extended via plugins to introduce new physical constraints (e.g., specific metabolic costs) without modifying the core engine.

    Extensible Compiler: I implemented a multi-pass compiler (Lexer → Parser → Semantic Analysis → IR → Linker → Emitter) to translate EvoASM into the spatial code of executable molecules.

        Plugin System: Each compiler phase is extensible. Users can inject custom optimization passes or syntax extensions via the plugin registry.

    Distributed Pipeline:

        Hot Path: The simulation core runs on a flattened int32 array for CPU cache locality.

        Cold Path: Data processing is decoupled via queues. Persistence, indexing, and analytics services are horizontally scalable.

        Analytics: The analysis frontend allows defining custom metrics via plugins to evaluate simulation runs.

Current Status & Research Roadmap:

A primordial replicator (written in EvoASM) is functional. Current simulations tend to result in equilibrium or "Grey Goo" scenarios. The platform can now be used to test specific mechanisms to overcome this:

    Genomic Stability: Testing fuzzy jump targets (SignalGP style) vs. absolute addressing.

    Thermodynamics: Implementing entropy/waste heat constraints for every instruction execution.

    Digital Eukaryogenesis: Testing organism-internal multi-threading (FORK opcode) to separate replication from "metabolic" background tasks.

    Signaling: Implementing fuzzy signaling between execution contexts.

Why I am sharing this: The project is fully Open Source (MIT License). I am looking for critical feedback on the architecture, but more importantly, I am seeking contributors. If you are a systems engineer or researcher interested in designing the "digital physics" (thermodynamics, metabolic constraints) required to push this system beyond stagnation, I would love to collaborate.

A Note on the Tech Stack & AI: While the architecture is human-designed, I used AI extensively as a force multiplier to handle the implementation details. Lesson learned: AI coding is powerful but requires strict architectural control and review to build clean, complex systems.

Request: Please let's keep the discussion here focused on ALife, system architecture, and evolutionary theory. If you want to chat about the "AI-assisted workflow", feel free to ping me on Discord!

Repo: https://github.com/evochora/evochora
Demo: http://evochora.org

Happy to answer questions about the VM design or the grid implementation.
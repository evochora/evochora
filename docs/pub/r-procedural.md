Hi all,

This ia typical an r/alife topic, but I thought due to the procedural nature, you might be interested as well.

I wrote Evochora in the last months. It is an attempt to provide a platform aimed at overcoming some of the hurdles OEE (Open-Ended Evolution) science currently faces. The core idea is to have embodied agents (organisms) in an n-dimensional environment. These agents execute custom-designed, low-level spatial code to navigate the environment with their Instruction Pointer (IP) and Data Pointers (DPs). To be clear: This is a serious simulation, not a game!

Crucially, organisms operate under (currently simple) thermodynamic laws to replicate themselves. There are no external hard-coded rewards or goal culling giving the evolution a dedicated direction. There is only rigorous (but artificial) physics that can be extended to investigate what kind of rules support emergent increasing complexity.

The system is still in an early stage, but these components are working:

**Multi-pass Compiler**: Translates custom assembly into spatial low-level instructions. Each phase is extensible via plugins. Reference: [https://github.com/evochora/evochora/blob/main/docs/ASSEMBLY\_SPEC.md](https://github.com/evochora/evochora/blob/main/docs/ASSEMBLY_SPEC.md)

**Virtual Machine**: Organisms use this to execute spatial instructions to navigate the shared n-dimensional environment. Features include: customizable number of general purpose registers (DRs), contextual registers (PRs, FPRs), location registers (to remember positions previously visited), and three stacks (Data, Call, Location). Plus an Energy Register (ER) for thermodynamic states. The VM is extensible with plugins currently only used for emitting energy to the environment, but can also be used to define mutational or any other kind of manipulation patterns.

**Data Pipeline**: Supports horizontal scaling for cloud distribution with an abstraction layer for shared resources (queue, storage, topic, database) and decouples the simulation's hot path from the cold path data processing.

**Web Frontends**: A Visualizer for inspecting the environment/assembly code tick-by-tick, an Analyzer for visualizing metrics across full runs, and finally a video renderer to render full simulation runs (see video above)

**Current Research Status**: There is a viable self-replicating primordial organism (as seen in the video). However, in the long run, the simulation tends to end in "Grey Goo." Runtime machine code manipulation often leads to damaged organisms executing tight loops that corrupt neighbors, causing a chain reaction. While this could be "patched" with fixed rules (killing instability), it probably sacrifices long-term evolvability—arguably a reason why systems like Tierra or Avida hit a complexity ceiling.

**The Roadmap (Why I seek for contributors)**: The system is designed to test which constraints (like thermodynamics) enable higher complexity. Ideas that might be promising:

* Detailed Thermodynamics: Introducing reaction chains (e.g., A + B -> Energy + Entropy) to see if trophic levels emerge (waste of one species becomes resource of another).
* Fuzzy Jumps (SignalGP-like): For genetic stability and signaling between execution contexts.
* Digital Eukaryogenesis: Allowing organisms to FORK execution contexts (threads) for background metabolism, coordinated via fuzzy signaling. This could be a path to true multicellularity.

**Links**:

Source Code: [https://github.com/evochora/evochora](https://github.com/evochora/evochora)

Live Demo: [http://evochora.org](http://evochora.org)

Scientific Context: There is a draft whitepaper with deeper details (Note: This document was refined with AI assistance): [https://github.com/evochora/evochora/blob/main/docs/SCIENTIFIC\_OVERVIEW.md](https://github.com/evochora/evochora/blob/main/docs/SCIENTIFIC_OVERVIEW.md)

**I am looking for contributors to help improve the compiler, physics, or pipeline and to discuss artificial physics that can lead to higher emergent complexity.**

*Full disclosure: I used AI tools to accelerate the coding of the Java boilerplate and the web frontends, but the architecture, the custom virtual machine design, and the 'physics' of the world are my own engineering. I'm happy to answer any technical questions about the tech stack or architecture*
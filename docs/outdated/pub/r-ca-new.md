Evochora: Proposal for a new platform to research evolutionary emergent complexity. Looking for feedback and contributors.

Hi everyone,

For the last 6 months, I've been working on Evochora, an Artificial Life platform to explore why digital evolution tends to stagnate. It's designed as an evolving research platform to identify and overcome complexity hurdles one by one. I am looking for constructive feedback and potentially even contributors.

*Core concept*
Organisms consist of executable spatial low-level assembly code residing directly in a shared n-dimensional grid – their code is their physical body. They must mechanically navigate this grid to survive and replicate. No fitness functions, no goal culling, only physics.

*Current Status*
Right now the system consistently produces and maintains stable populations of digital organisms without manual tuning or scripted balancing. Early versions collapsed into grey goo as organisms corrupted each other's code. Thermodynamic constraints solved this: organisms must actively manage energy and entropy to survive, creating population-level stability without punishing mutations.

*System Architecture*
Evochora is still early-stage, but designed for extensibility to evolve with future research needs.

Currently operational:
 * Compiler: Multi-pass architecture, plugin-extensible at each phase
 * Virtual Machine: Turing-complete with configurable registers/stacks
 * Data Pipeline: Decoupled hot path (simulation) and cold path (persistence, analytics), horizontally scalable
 * Web Frontends: Visualizer, analyzer, and video renderer

*What's next?*
 * Mutation Plugins: Replication is currently exact. I’m planning a plugin-based mutation system to experiment with different mutation types from simple bit flips during replication to cosmic radiation (environmental noise) and complex genomic rearrangements.

 * Flattened Fitness Landscape: Currently, most mutations would be catastrophic. I plan to implement mechanisms that make code more robust to mutations, allowing gradual evolutionary exploration rather than instant death.

 * Cloud Scaling & Delta Compression: Runs of ~15M ticks on 4000x3000 already work locally. Next: cloud backends for existing abstractions + delta snapshot compression.

 * Signaling & Concurrency: Allow organisms to spawn internal execution contexts (threads) with signaling for coordination—exploring whether this could lead to more complex organization and, speculatively, emergent multicellularity. 

*The Ask*:
Open Source, MIT licensed. I'm looking for critical feedback and collaborators, especially for scaling, storage, mutation design, and signaling models.

Tech Stack: Java 21/Gradle, but willing to migrate certain parts to other platforms.
Built with AI assistance (architecture and scientific direction are mine). Happy to discuss technical details here; AI workflow details on Discord if interested.

*Links*:

 * Repo: https://github.com/evochora/evochora
 * Demo: https://evochora.org (live simulation + video)
 * Discord: https://discord.com/invite/t9yEJc4MKX
 * Assembly Spec: https://github.com/evochora/evochora/blob/main/docs/ASSEMBLY_SPEC.md
 * Whitepaper (draft!): https://github.com/evochora/evochora/blob/main/docs/SCIENTIFIC_OVERVIEW.md

Happy to discuss any tech details or the scientific approach!
Update:

(shorter version below, but that is AI generated!)

For some people the previous version was too polished. Here is the raw technical breakdown in my own words:

This is the attempt to provide a platform that is aiming to help to overcome some of the hurdles OEE science currently face. The idea is to have embodied agents (I call them organisms) in an n-dimensional environment, that execute custom designed low-level spatial code to navigate the environment with their instruction pointer (IP) and their data pointers (DPs) under thermodynamics laws to replicate themselves. There is no external hard coded rewards or goal culling that could give the evolution a dedicated direction. There is only rigorous (but artificial) physics that can easily be extended and modified to investigate what kind of physics actually support emergent increasing complexity, probably and hopefully overcoming some of the hurdles towards OEE.

The system is still in early stage. Many things still need to be build, but the following things are already working:
- Multi pass compiler that can translate the custom assembly into spatial low-level instructions the organisms VM can execute. Each phase of the compiler is extensible with a plugin system. The assembly reference can be found here: https://github.com/evochora/evochora/blob/main/docs/ASSEMBLY_SPEC.md

- Virtual Machine the organisms use to execute the spatial instructions no navigate the shared n-dimensional environment with their IP and DPs and manipulate their internal state. It has a customizable amount of general purpose registers (DRs) and a customizable amount of contextual registers for procedure execution (PRs, FPRs). Additionally it has location registers to remember positions it already visited before. Also there is a general purpose data stack (DS), a call stack (CS) and a location stack (LS). Last but not least it has an energy register (ER) for simple thermodynamic states. The VM is extensible with plugins currently only used for emitting energy to the environment, but can also be used to define mutation patterns, or any other kind of manipulation patterns.

- There is a configurable data pipeline that supports horizonal scaling of data processing services for cloud distribution and an abstraction layer for shared resources between these services (queue, storage, message bus, database).

- Last but not least there are different specialized web frontends: A Visualizer that can be used to inspect the environment and the organisms state in every tick of the simulation and also provides debugging information for the assembly code organisms execute. The visualizer is currently only 2D but all previous steps are dimensional agnostic. Then there is an Analyzer that can be extended with analytics plugins to calculate and visualize defined metrics across a full simulation run. Finally a video renderer, that can output an simulation run as you see above.

The current state of the simulation is that there is a viable self-replicating primordial, as you see in the video above. In the long run it still ends up in grey goo. As runtime machine code manipulation too often lead to organisms that only execute short loops potentially corrupting other organism pushing them into the same amok state, which leads to a runaway chain reaction. This can be overcome by introducing fixed rules to prevent such organisms to die fast as soon as the end up in this state, but it is plausible that the gained populations stability this gained by scarifying long-term evolvability. It is arguable that this is a reason why systems like Tierra or Avida were not able to show emergent increasing complexity.

So the system is designed to test which measures (like thermodynamic constraints) are necessary to enable the simulation to show higher emergently evolved complexity. Here are a few ideas, that could be tested with some implementation effort:

- introduce more detailed thermodynamic rule: The machine code executed by the VM is already spatial and categorized like molecules of different types (currently CODE, DATA, ENERGY and STRUCTURE). It could be tested, if we can achieve niche constructions if we introduce reaction chains of these molecules (e.g., A + B -> Energy + Entropy), probably even trophic levels could be emerge, where the waste of one species is the resource of another.

- introduce fuzzy jumps (SignalIGT-like) for more genetic stability which can also be used for signaling between multiple execution contexts explained in the next point.

- introduce some kind of digital eukaryogenesis, where organisms can spawn additional execution contexts to execute certain sub routines in background threads and use the previously explained fuzzy signaling for coordination of these threads. If done well, this could probably the path to true multicellularity, which be a break-through major transition OEE is trying to show for such long time already.

There are many more ideas that could be tested! A scientific discussion about the potential of different ideas is very much wanted!

That is also why I am seeking for contributors! As basically every single part of the full platform can be improved and extended: Compiler design, digital physics, data pipeline, frontends, etc.

You can find the full source code here: https://github.com/evochora/evochora

There is a running demo system where you can use above mentioned visualizer and analyzer for a previously executed simulation run: http://evochora.org

If you are interested to dive deeper into the scientific discussion and also see more details about the tech setup there is a draft whitepaper explaining all this in much higher depth: (CAUTION!!! it was generated with the help of AI!) https://github.com/evochora/evochora/blob/main/docs/SCIENTIFIC_OVERVIEW.md

Happy for any kind of constructive critique, questions or contribution!

---

Hi everyone,

We all love Cellular Automata for their emergent complexity arising from simple, static rules (like Game of Life). But I wanted to push the concept further: What happens if the "update rule" itself is subject to evolution?

I spent the last months building Evochora, a distributed ALife laboratory to investigate exactly this.

Live Demo: http://evochora.org

The Concept:

Embodied Code: Cells are not just states (0 or 1). They contain "Molecules" representing assembly instructions (EvoASM).

Local VM: Every active agent runs a virtual machine loop. To reproduce, it must run a complex algorithm (~1500 instructions) to harvest energy tokens from the grid and mechanically copy its own code into neighboring cells.

No God Mode: There is no global supervisor. Stability (or chaos) emerges purely from the interactions of these local programs.

The Goal (Why I need you): This project is an attempt to overcome the stagnation in the seek for Open-Ended Evolution (OOE). We don't claim to have all the answers, but we want to provide a rigorous platform to investigate the physical prerequisites for the next big step.

Currently, our physics allows for self-replication, but without strict thermodynamic constraints, agents evolve into aggressive "Grey Goo" loops (as seen in the video) that cannibalize the grid.

We are looking for contributors to help us design the fundamental conditions (Thermodynamics, Signaling, Multi Execution Contexts, Reaction Chains) required to turn this chaotic soup into a stable ecosystem capable of major transitions (like multicellularity).

The Tech: The core is a high-performance engine written in Java 21, designed to run on flattened n-dimensional grids (int32 arrays) for maximum cache locality. It is decoupled from the distributed data processing part, which is architectured for massive horizontal scale.

Join the Experiment: I am looking for contributors—whether you are into low-level engineering, simulation physics, or biological theory—to help us design these conditions. We want to move from "Grey Goo" to "Digital Eukaryotes".

Repo: https://github.com/evochora/evochora
Scientific Background: https://github.com/evochora/evochora/blob/main/docs/SCIENTIFIC_OVERVIEW.md

Happy to answer questions about the VM architecture or the n-dimensional grid implementation!
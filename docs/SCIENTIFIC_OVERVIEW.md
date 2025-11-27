# Evochora: A Collaborative Platform for Research into the Foundational Physics of Open-Endedness and Major Transitions

## Abstract

Research in Artificial Life has long sought to understand the principles governing the emergence of complexity, yet it remains an open question which evolutionary outcomes are universal and which are contingent upon Earth's history. Landmark systems have explored this by demonstrating the emergence of simple ecologies (Tierra) or the evolution of complex, pre-defined functions (Avida). However, the field has been fundamentally challenged by the difficulty of achieving sustained, open-ended evolution. Most approaches are constrained by their fixed, hard-coded "physics," causing digital worlds to either stagnate, converge on narrow optima, or follow artificially directed paths which is breeding not evolution and prevents the exploration of a deeper question: what properties must an environment possess for complex innovation to arise in the first place?

We introduce Evochora, an open-source platform designed to investigate this question. Evochora provides a rich, n-dimensional environment where the rules of physics and evolution are not pre-supposed but are themselves objects of research. We depart from traditional models by introducing embodied agency, where organisms must navigate their world through distinct instruction-executing and data-manipulating pointers (IP/DPs). This agency is supported by a modular virtual machine offering a rich set of low-level capabilities—including a versatile array of registers and stacks—providing evolution with a powerful toolkit from which to construct higher-order behaviors without them being explicitly predefined. Survival depends on actively foraging for ENERGY while overcoming STRUCTURE obstacles, creating a more grounded form of selection pressure.

The platform's core architecture invites the scientific community to collaboratively design and test the foundational laws of a digital universe. Evochora thus serves not as a single simulation, but as a collective instrument for large-scale, reproducible experiments, with the ultimate goal of demonstrating that higher-order complexity can arise emergently, and to identify the fundamental prerequisites that make it possible.

**Live visualization:** [http://evochora.org/](http://evochora.org/)

## 1. Introduction

One of the most profound goals in science is to understand whether the evolutionary path taken on Earth—from self-replication to multicellularity and cognition is a unique accident or the result of a universal principle [(Maynard Smith & Szathmáry, 1995)](#ref-maynard-smith-1995). As our only empirical example, life on Earth offers a single data point, making it impossible to distinguish universal laws from historical contingencies. The field of Artificial Life (A-Life) addresses this by creating "digital universes" where evolution can be re-run, enabling us to explore what fundamental properties of a world are necessary to ignite the sustained emergence of complexity.

Despite seminal progress, achieving this goal has proven elusive. The history of the field can be seen as a series of strategic attempts to overcome the persistent challenge of evolutionary stagnation and achieve true open-ended evolution (OEE), loosely defined as the continual production of novel organisms and behaviors [(Bedau et al., 2000)](#ref-bedau-2000). Seminal systems like Tierra demonstrated that simple ecological dynamics could emerge spontaneously [(Ray, 1991)](#ref-ray-1991), but their evolution rarely surpassed this initial level of complexity. In response, platforms like Avida introduced extrinsic rewards for pre-defined computational tasks [(Ofria & Wilke, 2004)](#ref-ofria-2004), successfully driving the evolution of complex functions but sacrificing the open-endedness that is the hallmark of natural evolution. Subsequent research has explored other avenues: some have focused on complex, dynamic environments to force continuous adaptation; others on sophisticated genotype-phenotype mappings, such as evolving virtual creatures [(Sims, 1994)](#ref-sims-1994), or continuous cellular automata like **Lenia** [(Chan, 2019)](#ref-chan-2019), which produce striking morphological complexity but often lack the semantic richness required for higher-order cognitive or functional evolution. A common thread, however, unites these diverse approaches: the fundamental rules of the world—the 'physics', the available organismal building blocks, the nature of interaction—are ultimately static and imposed by the designer.

This paper introduces Evochora, an open-source research platform architected to address this core limitation directly. We propose a shift in approach: instead of designing a world with another fixed physics, we provide a rich, spatial environment with a versatile set of low-level capabilities, and make the rules of this world themselves an object of scientific inquiry. The platform is designed to facilitate large-scale, long-duration simulations, recognizing that the emergence of complexity requires vast computational exploration. This paper will detail the core architecture of Evochora, including its unique model of embodied agency and its extensible physics, and outline the research avenues it opens for a collaborative investigation into the prerequisites for open-ended evolution.

## 2. The Evochora Architecture

The Evochora platform is architected from the ground up to serve as a flexible and high-performance testbed for exploring the prerequisites of open-ended evolution. Its design is guided by the principles of modularity, spatial embodiment, and extensible physics. This section details the core, currently implemented components of the system.

### 2.1 The N-Dimensional World and Typed Molecules

The foundation of Evochora is a world defined by an n-dimensional grid of fundamental units called **Molecules**. The number of dimensions (n) is fully configurable, allowing for experimentation in spatial environments from 2D planes to higher-dimensional spaces. The world's topology is also configurable and can be set to be either bounded or toroidal. Each Molecule is represented by a 32-bit integer, with a configurable number of bits allocated to its type and its value. This extensible type system forms the basis of the world's physics. The four primary types are:

- **CODE**: A Molecule whose value represents a virtual machine instruction. A value of 0 corresponds to the NOP (No Operation) instruction and represents empty space.
- **DATA**: A Molecule whose value serves as a numerical argument for CODE instructions.
- **ENERGY**: A resource Molecule whose value represents a quantity of energy an organism can absorb.
- **STRUCTURE**: A Molecule that acts as a physical obstacle, costing energy to interact with.

This core system is designed for expansion. The ability to introduce new Molecule types opens up significant research avenues, such as evolving a complex "chemistry" where Molecules must be combined to create higher-order resources, or a concept of "waste" that could be recycled by other organisms.

### 2.2 Embodied Agency and the Virtual Machine

Organisms in Evochora are not abstract computational entities but embodied agents. This principle is realized through a sophisticated Virtual Machine (VM) that endows each organism with a rich internal state and a unique model for interacting with the world. A complete specification of the virtual machine, its instruction set, and the assembly language is provided in Appendix A.

The VM separates an organism's "processor" from its "actuators":

- The **Instruction Pointer (IP)** executes the CODE Molecule at its current position. It moves "forward" according to a local Direction Vector (DV), which the organism can modify at runtime, allowing for complex, n-dimensional code layouts.
- **Data Pointers (DPs)** are the organism's "limbs" used for all direct world interactions, like reading (PEEK) and writing (POKE) Molecules.

To support complex behaviors, the VM provides a rich set of internal components, whose counts and sizes are configurable:

**Registers**: A versatile array of registers is available, including:
- General-purpose **Data Registers (DRs)**
- Procedure-scoped registers (**PRs**)
- Formal parameter registers (**FPRs**)
- **Location Registers (LRs)** for storing n-dimensional coordinates

**Stacks**: Three distinct stacks manage program flow and memory:
- **Data Stack (DS)** for general computation
- **Call Stack (CS)** for procedure calls
- **Location Stack (LS)** dedicated to storing coordinates

This architecture provides evolution with a powerful but low-level toolkit. Higher-order behaviors are not predefined; they must emerge from the combination of these fundamental capabilities. A key innovation enabled by this design is the organism's navigation model. While basic DP movement is local and step-wise (SEEK), an organism can store its current DP coordinates in its LRs or push them onto the LS. These stored locations can then be jumped to directly. This design elegantly solves a key challenge: it allows for efficient, non-linear movement patterns and complex spatial routines without breaking the fundamental principle of locality, as organisms can only jump to places they have physically visited before.

Taken together, these design choices enforce a strong principle of **locality and physical immobility**. An organism has no internal knowledge of its absolute coordinates, only its local surroundings. Furthermore, Evochora deliberately omits a high-level instruction for moving an organism's entire physical footprint (CODE, DATA and STRUCTURE Molecules). Such an instruction would not only present significant technical challenges (e.g., collision detection in n-dimensions, resolving ownership of shared code) but would fundamentally contradict the platform's philosophy of providing only low-level building blocks. This omission is a conscious design choice that focuses the evolutionary dynamics on sessile organisms and their local interactions, making it a powerful experimental framework for investigating the origins of aggregation and multicellularity.

### 2.3 Metabolism, Survival, and Ownership

Survival in Evochora is governed by a simple but powerful metabolic economy. Every instruction executed costs a specific amount of energy, which is deducted from the organism's internal **Energy Register (ER)**. If an organism's ER drops to zero, it "dies" and its components are removed from the simulation. To survive, an organism must use its DPs to actively forage for ENERGY Molecules. Executing a PEEK instruction on an ENERGY Molecule adds its value to the organism's ER. Conversely, STRUCTURE Molecules act as energy sinks; interacting with them costs additional energy. This creates a direct, intrinsic selection pressure for organisms to evolve efficient exploration and resource acquisition strategies.

This dynamic is further enriched by a **Molecule ownership model**. DPs are generally only able to move through empty space or Molecules that are owned by their parent organism. This makes self-replication a non-trivial challenge, as an organism must not only copy its code but also manage the space it occupies. The mechanism by which ownership is inherited from parent to child is a key area of open research within the platform. The current implementation allows a child organism to treat its direct parent's territory as its own to prevent it from becoming trapped upon creation, but more complex strategies, such as a GIFT instruction to bequeath specific Molecules, are envisioned as future research avenues.

### 2.4 Extensible by Design: The Plugin Architecture

A core design philosophy of Evochora is that the fundamental rules of the world should be modular and themselves subject to scientific investigation. This is implemented via a **plugin architecture**. The system for energy distribution serves as the primary example of this concept. Instead of a single, hard-coded rule for how energy enters the world, researchers can choose between different, swappable plugins. Currently implemented models include a "random light" plugin, where ENERGY Molecules appear at random locations in each simulation tick, and a "geyser" model, where energy is periodically emitted from fixed points in the world. This modular architecture is designed as a general-purpose mechanism for extending the world's physics and is planned to be used for implementing diverse mutation models in future work.

### 2.5 The Primordial Organism: A Case Study in Viability

The successful implementation of a viable, self-replicating primordial organism serves as a practical demonstration of the architecture's capabilities. This ancestor holistically solves the intertwined challenges of mechanics, metabolism, and ecology.

Mechanically, it uses a `STRUCTURE` shell as a physical boundary, which acts as a robust termination signal for its low-level copying algorithm and protects itself against hostile behavior of other organisms, reminiscent of the "Lipid World" hypothesis for prebiotic containment [(Segré et al., 2001)](#ref-segre-2001). Metabolically, it follows a strict energy-positive feedback loop, employing an efficient exploration strategy to gather a significant energy surplus before investing in the costly process of replication. Ecologically, it secures space for its offspring by actively clearing a contiguous area, demonstrating how simple instructions can be combined to produce complex, environment-altering behavior. This functional ancestor provides a stable foundation from which evolutionary dynamics can emerge.

## 3. Open Challenges and Avenues for Contribution

With a stable platform and a viable primordial organism, the focus of the project now shifts from foundational engineering to exploring the emergent evolutionary dynamics that this system makes possible.

This section outlines the next frontier of open challenges and promising research avenues. Each represents a key area where the platform's unique architecture provides a powerful testbed for investigation. We present these not as a fixed roadmap, but as an open invitation to the scientific community to collaborate, experiment, and help shape the future of this digital universe.

### 3.1 The Physics of Stability: Mitigating Error Catastrophe and "Grey Goo"

One of the most immediate challenges observed in early Evochora experiments is the phenomenon of destructive interference, colloquially known as "Grey Goo." In a crowded spatial environment, primordial organisms that blindly clear paths for their offspring often inadvertently delete or corrupt the genomes of their neighbors. Because the neighbors share a similar genetic lineage, they often possess the same blind replication strategies. Once corrupted by mutation or damage, these organisms can devolve into highly efficient "deleters," stripping the world of complex structure in a runaway chain reaction. This mirrors the theoretical "Error Catastrophe" [(Eigen, 1971)](#ref-eigen-1971), where the accumulation of errors outpaces the selection for functional information.

Evochora provides the ideal testbed to investigate solutions to this stability problem, weighing immediate stability against long-term evolutionary potential.

The "Penalty" Dilemma: Initial experiments have demonstrated that imposing strict energy penalties for executing invalid instructions successfully mitigates the immediate collapse by rapidly culling malfunctioning organisms. However, we hypothesize that this approach may be evolutionarily shortsighted. It effectively sanctions "junk code"—non-functional genomic regions that are widely considered a crucial reservoir for neutral evolution [(Lynch, 2007)](#ref-lynch-2007). A physics that aggressively punishes any deviation from functionality might stabilize the world but inadvertently freeze evolution on local optima by preventing the genetic drift necessary for innovation.

Consequently, we propose investigating alternative mechanisms that ensure stability without suppressing genomic exploration:

Behavioral Hypothesis: Is a "cautious" seed sufficient? We propose testing whether the error catastrophe can be avoided simply by designing the primordial organism with a "civil" replication strategy—checking cell contents before overwriting. The critical research question is whether this initial stability can be maintained or if it is inherently unstable. Since "cautious" replication is slower and more costly than "blind" aggression, we must determine if aggressive mutants will inevitably evolve and trigger a "Grey Goo" collapse, proving that behavioral constraints are insufficient without physical enforcement.

Physical Hypothesis: Must stability be legislated by the laws of physics? Alternatively, we propose experimenting with the fundamental rules of the world to enforce stability through economics rather than prohibition.

Cost of Aggression: We aim to enforce stability by making the act of destruction itself prohibitively expensive. Specifically, we propose increasing the energy cost for modifying or deleting Molecules that belong to a living foreign organism. This economic deterrent acts as a physical enforcement of "property rights." To implement this targeted penalty, we first require a refined ownership model (e.g., using a GIFT instruction) to rigorously distinguish between the legitimate transfer of territory to offspring and the hostile overwriting of a neighbor.

Genomic Robustness via Fuzzy Addressing: To prevent slight mutations from turning functional builders into destroyers, we propose implementing "Fuzzy Jumps" (referencing concepts from Avida and SignalGP). Instead of jumping to absolute addresses (which break easily under mutation), organisms would jump to "anchors"—patterns in the code. This makes the genome resilient (neutral) to small shifts, raising the threshold for error catastrophe without punishing the existence of non-functional code segments.

By comparing these approaches, Evochora allows us to experimentally determine the minimal physical constraints required to prevent the collapse of a digital ecosystem while maintaining the freedom for neutral evolution.

### 3.2 The Evolution of Evolvability: A Pluggable Mutation Model

Mutation is the ultimate source of all evolutionary innovation, yet its implementation in most artificial life platforms has remained surprisingly simplistic. Typically, mutation is modeled as a single, globally applied mechanism, such as random bit-flips in an organism's genome occurring at a fixed rate (e.g., [(Ray, 1991)](#ref-ray-1991); [(Ofria & Wilke, 2004)](#ref-ofria-2004)). While sufficient for local optimization, this limited approach ignores the crucial concept of evolvability—the capacity of a system for adaptive evolution [(Wagner, 2005)](#ref-wagner-2005).

Theoretical biology suggests that evolvability is not a constant, but a property that itself evolves. Different types of mutational operators allow populations to traverse "genotype networks" in different ways, finding paths through the fitness landscape that simple point mutations might miss. By hard-coding a specific mutation model, simulations inadvertently fix the topology of this landscape, potentially blocking the very routes to higher complexity we seek to find.

Evochora addresses this by treating the rules of variation as a first-class experimental variable through a pluggable mutation system. This allows researchers to experimentally deconstruct the components of evolvability by comparing distinct mutation regimes:

- **Background "Cosmic" Radiation**: A global, environment-driven mutation model that decouples variation from replication, testing hypotheses about stability in high-radiation environments.

- **Replication Fidelity (Copy Error)**: Linking mutation strictly to the POKE instruction. This allows for the evolution of "mutator" strains or highly conserved lineages, putting the control of mutation rate partially under the organism's control.

- **Somatic Mutation**: A plugin that can cause transient bit-flips in an organism's internal state (its registers or stacks), exploring the evolutionary consequences of non-heritable variation.

- **Genomic Rearrangements**: Beyond bit-flips, operators like duplication, deletion, and inversion are major drivers of innovation in nature [(Lynch, 2007)](#ref-lynch-2007). In Evochora, these macro-mutations could allow organisms to bridge wide gaps in the fitness landscape or rapidly functionally diversify duplicated genes (subfunctionalization), a key mechanism for generating complexity that simple point mutations cannot replicate.

By enabling the comparative study of these mechanisms, Evochora allows us to ask not just how organisms evolve, but how the capacity to evolve is shaped by the physical laws of mutation.

### 3.3 The Bioenergetics of Complexity: A Digital Eukaryogenesis

A central puzzle in evolutionary biology is why life on Earth remained microscopic and relatively simple for billions of years before the sudden explosion of complex eukaryotic life. The leading hypothesis, the **"Energetics of Genome Complexity"** [(Lane & Martin, 2010)](#ref-lane-martin-2010), posits that prokaryotes are fundamentally constrained by their bioenergetics: a single cell cannot expand its genome size (and thus complexity) significantly because its energy generation is limited by its surface area. The transition to complexity (Eukaryogenesis) was only possible through endosymbiosis—the internalization of energy-producing bacteria (mitochondria)—which broke this barrier by internalizing the surface area for energy production.

Evochora is uniquely architected to test if a similar **"Processing Power Constraint"** exists in digital life. A traditional single-threaded digital organism faces a trade-off analogous to the prokaryotic constraint: a single Instruction Pointer (IP) must be shared between "metabolic" maintenance (foraging for energy) and "complex" behaviors (navigation, construction). As the genome grows more complex, the cost of maintaining it (execution cycles) outstrips the organism's ability to gather energy sequentially.

To investigate this, Evochora introduces a mechanism for **Digital Eukaryogenesis** via the `FORK` instruction. While typically used for reproduction, `FORK` can be adapted to spawn a secondary, fully independent VM context (complete with its own registers, stacks, and DPs) that runs concurrently on the shared genetic code within the *same* organism body. This allows an organism to effectively internalize a "symbiont"—a dedicated thread focused solely on energy acquisition (like a mitochondrion)—freeing the main IP to execute complex genomic logic without starving. We hypothesize that this internal parallelization is not just a performance optimization, but a necessary prerequisite for the evolution of higher-order complexity.

### 3.4 From Endosymbiosis to Multicellularity: The Signaling Exaptation

If organisms evolve internal parallelism (multiple IPs) to solve their energetic constraints, they face a new challenge: coordination. The main thread (nucleus) and the energy thread (mitochondrion) must synchronize their actions via signals. This leads to a profound hypothesis regarding the **Major Transition to Multicellularity** [(Maynard Smith & Szathmáry, 1995)](#ref-maynard-smith-1995): we propose that the signaling machinery evolved for *internal* coordination acts as a **pre-adaptation (exaptation)** for *external* sociality.

Specifically, we predict a natural evolutionary trajectory consisting of three distinct phases:

1.  **Stage I (Prokaryotic):** Simple, single-threaded organisms limited by processing constraints (one IP).
2.  **Stage II (Eukaryotic):** Evolution of internal parallelism (endosymbiosis) via `FORK` to break energy constraints, requiring the evolution of an internal signaling protocol.
3.  **Stage III (Multicellular):** The "externalization" of this signaling protocol to facilitate a **Fraternal Transition** [(Moreno & Ofria, 2022)](#ref-moreno-2022), allowing genetically identical organisms to coordinate instantly and form higher-level individuality.

In Evochora, the instructions used to send a signal to an internal thread are structurally identical to those used to signal a neighbor. This unified architecture supports two critical modalities required for complex tissue formation without needing rigid, hard-coded protocols:

* **Broadcast Signaling:** Equivalent to biological *quorum sensing* [(Bonabeau et al., 1999)](#ref-bonabeau-1999), allowing organisms to react to local gradients or group density (e.g., "Food source found").
* **Targeted References:** By establishing "fuzzy locks" with specific neighbors (e.g., via kinship upon replication [(Hamilton, 1964)](#ref-hamilton-1964) or direct contact), organisms can form persistent structural bonds or channels for direct resource transfer, mimicking the tight coupling of *multicellular tissues*.

**Experimental Strategy: Skipping the "Boring Billion"**
Acknowledging that the random evolution of such a complex "eukaryotic" architecture could take prohibitively long (mirroring the "Boring Billion" years on Earth), Evochora allows researchers to bypass this bottleneck. We propose an experimental setup seeded with a **multi-threaded primordial organism**. This ancestor is explicitly programmed to execute a `FORK` instruction immediately upon initialization, establishing a dual-context architecture where a secondary instance is dedicated to energy acquisition while the primary instance manages replication. This interventionist strategy allows us to skip **Stage I** and start directly at **Stage II**, testing the hypothesis that organisms with pre-existing internal coordination channels are primed for the rapid emergence of multicellularity (Stage III).

### 3.5 Beyond Fixed Resources: Digital Chemistry and Thermodynamics

Current A-Life systems often model "energy" as a simplistic, abstract counter. However, in physical reality, life is driven by **Thermodynamics**: organisms maintain their internal order (low entropy) only by continuously dissipating energy and increasing the entropy of their environment [(Schrödinger, 1944)](#ref-schrodinger-1944). Recent theoretical work further formalizes this link, viewing organisms as "Thermodynamic Agents" that must acquire information to lower their internal entropy, establishing a direct equivalence between information processing and thermodynamic work [(Gebhardt et al., 2019)](#ref-gebhardt-2019). This flow of energy through matter drives the emergence of complex trophic webs, where the high-entropy waste of one organism becomes the low-entropy resource for another.

Evochora aims to simulate these dynamics by expanding its physics into a fully-fledged **Digital Chemistry** [(Dittrich et al., 2001)](#ref-dittrich-2001).

**From Resources to Reactions**: We propose replacing the generic `ENERGY` Molecule with a property-based system where Molecules have distinct energetic potentials and stabilities. Energy acquisition is no longer a simple `PEEK` operation but a **Reaction**, where an organism acts as a catalyst to combine specific environmental substrates. This allows for the implementation of **Reaction Chains**, where complex metabolic pathways must evolve to unlock high-yield energy sources.

**Niche Construction and Ecological Feedback**: This chemical model naturally leads to **Niche Construction** [(Odling-Smee et al., 2003)](#ref-odling-smee-2003). As organisms react Molecules, they inevitably produce byproducts ("waste" or modified structures). Over time, these modifications accumulate, fundamentally altering the environment. A "waste" product might accumulate until a new mutant evolves the metabolic machinery to consume it, closing the loop and creating a new ecological niche. This echoes the dynamics seen in systems like **Chromaria** [(Soros & Stanley, 2014)](#ref-soros-2014), where the physical remains of organisms become environmental features that shape the evolutionary landscape for subsequent generations. In Evochora, the persistence of dead code already provides a primitive form of this feedback, which we aim to expand into a full chemical cycle.

By enforcing thermodynamic constraints—where every action generates waste heat or material byproducts—Evochora allows us to investigate how the necessity of entropy reduction drives the evolution of efficient, circular ecosystems and complex food webs.

## 4. Computational Framework and Experimental Feasibility

The grand scientific ambitions of Evochora are predicated on the ability to run large-scale, long-duration simulations that are both computationally feasible and scientifically rigorous. This section outlines the computational framework designed to meet these challenges, addressing its performance architecture, data handling capabilities, and the provisions for reproducible analysis.

The following diagram illustrates the high-level data flow architecture:
```
┌────────────────────────────┐
│      SimulationEngine      │
└─────────────┬──────────────┘
              │ (TickData)
              ▼
┌────────────────────────────┐
│        Tick Queue          │
└─────────────┬──────────────┘
              │ (Batches)
              ▼
┌────────────────────────────┐
│    Persistence Service     │ (Competing Consumers)
└─┬─────────────────────┬────┘
  │ (Data)       (BatchInfo Event)
  │                     │
  ▼                     ▼
┌───────────┐    ┌───────────┐
│  Storage  │    │  Topics   │
└─────┬─────┘    └──────┬────┘
      │ (Reads)    (Triggers)
      │                 │
      └────────┬────────┘
               │
               ▼
┌────────────────────────────┐
│      Indexer Services      │ (Competing Consumer Groups)
└─────────────┬──────────────┘
              │ (Indexed Data)
              ▼
┌────────────────────────────┐
│          Database          │
└─────────────┬──────────────┘
              │ (Queries)
              ▼
┌────────────────────────────┐
│       Web Visualizer       │
└────────────────────────────┘
```

### 4.1 Performance and Scalability Architecture

The core simulation is designed for high performance. The raw, in-memory simulation engine can achieve over 300,000 instructions per second on standard consumer hardware. However, for a full experiment with the entire data pipeline active (including persistence and indexing), the sustainable throughput is closer to 10,000 instructions per second, as the system becomes I/O-bound. The primary computational load scales linearly with the number of active organisms (O(N)), as each organism executes one instruction per simulation tick.

To move beyond the limitations of single-core execution, the simulation tick is architected in three distinct phases:

1.  **Plan**: All organisms concurrently determine their next instruction.
2.  **Resolve**: A synchronous conflict resolver identifies and mediates competing claims on world resources (i.e., multiple organisms attempting to write to the same Molecule).
3.  **Execute**: All non-conflicting instructions are executed concurrently.

This design explicitly anticipates parallelization. While the "Plan" and "Execute" phases are embarrassingly parallel, multithreading the simulation engine itself is planned for a future distributed cloud architecture. In the current in-process mode, available CPU cores are already fully utilized by the other concurrent services (e.g., Persistence, Indexing), making parallelization of the engine alone inefficient. For massive-scale experiments, a long-term vision involves partitioning the world into spatial regions managed by separate, distributed compute nodes. While this introduces synchronization challenges at the boundaries, the principle of locality inherent to organism behavior is expected to minimize inter-node communication, following the principles of **Indefinite Scalability** demonstrated by the **Moveable Feast Machine** [(Ackley, 2013)](#ref-ackley-2013), making this a viable path for future scaling.

### 4.2 Data Pipeline and Reproducibility

Large-scale simulations can generate significant data volumes, up to 25 GB per hour for raw data and up to 50GB for fully indexed data depending on configuration. The Evochora data pipeline is a decoupled, asynchronous system built on a foundation of abstract **Resources** to handle this throughput. The flow is designed for scalability: the `SimulationEngine` writes `TickData` messages to a queue. Multiple `PersistenceService` instances can act as competing consumers on this queue, writing data in batches to a durable storage resource. Downstream, various `IndexerService` types consume this stored data—again as competing consumer groups to build specialized indexes that are written to a database. This data powers the web-based visualizer. A key architectural principle is that all services are written against abstract resource interfaces (e.g., for queues and storage), allowing the underlying implementation to be seamlessly swapped from a high-performance in-process setup to a cloud-native, distributed one (e.g., using Redpanda and S3) without changing any service code.

Scientific rigor is ensured through **full determinism**. All sources of randomness use a fixed seed, and the conflict resolution mechanism is deterministic (currently favoring the organism with the lower ID), guaranteeing that an experiment is perfectly reproducible.

The roadmap for enhancing the data pipeline focuses on cloud-native scalability. This includes replacing the in-memory queue with a message bus (e.g., AWS SNS), enabling the Persistence Service to be scaled out across multiple machines writing to shared storage (e.g., AWS S3). A key planned feature is the ability to resume a simulation from a stored state at tick n. This is a relatively simple addition that will enable the use of cost-effective spot instances for very long-running experiments and introduces a research trade-off between storing full snapshots for random access versus storing deltas to reduce data size.

### 4.3 Analysis and Visualization

Raw simulation data is processed by a scalable **Indexer Service** that transforms it into a queryable format suitable for analysis. While this process is computationally intensive, it is also highly parallelizable and typically only needs to be run on specific, interesting time-slices of a simulation's history.

The primary analysis tool is a sophisticated, web-based visualizer, a live demo of which is available at [http://evochora.org/](http://evochora.org/). It allows researchers to step through a simulation tick-by-tick, visualizing the state of the world and inspecting the internal state (registers, stacks, etc.) of every organism. While the simulation runtime is fully n-dimensional, the current implementation of the visualizer is limited to visualizing 2D worlds. For primordial organisms created with the provided compiler, the visualizer provides source-level inspection capabilities, linking the executing machine code back to the original, human-readable assembly language. Additionally, the platform can render simulation runs as videos, providing a powerful tool for observing long-term dynamics. Future work will focus on developing higher-level statistical analysis services to automatically extract population-level dynamics. Specifically, we aim to apply **Assembly Theory** [(Sharma et al., 2023)](#ref-sharma-2023) to mathematically quantify the emergence of selection and complexification within the digital universe, distinguishing true evolutionary innovation from random combinatorial events.

## 5. Conclusion

We have introduced Evochora, a new open-source platform designed to address a fundamental challenge in Artificial Life: the exploration of the prerequisites for open-ended evolution. We have argued that the fixed, hard-coded "physics" of previous systems has limited the potential for digital evolution to achieve the kind of sustained, innovative complexity seen in nature. Evochora's core architecture, founded on the principles of embodied agency in an n-dimensional world and a modular, extensible physics, provides a new framework for investigating these foundational questions. The platform is not presented as a final answer, but as a robust and scalable instrument for a new kind of collaborative research.

The design choices, particularly the inherent physical immobility of organisms, are not limitations but deliberate experimental setups. By focusing the evolutionary pressure on local interactions between sessile agents, Evochora creates a powerful testbed for investigating some of the most profound questions in evolutionary biology, such as the emergence of cooperation, specialization, and potentially, major evolutionary transitions like multicellularity. The avenues for future research—from evolving a digital chemistry to implementing diverse mutation models—highlight the platform's long-term vision.

Evochora is therefore more than a simulation tool; it is an open invitation to the scientific community. The project is in an early stage, and many of the most exciting challenges—both scientific and computational—lie ahead. We welcome and encourage contributions of all kinds, from participating in scientific discussions about the "laws" of our digital universe to contributing to the open-source development of the platform itself. We believe that the search for the principles of life, digital or otherwise, is a collaborative endeavor, and we offer Evochora as a shared instrument for that quest.

## 6. Project Status, Roadmap, and Community
**Current Status**: Evochora is a fully operational, feature-rich platform with a stable VM, compiler, and data pipeline. A viable primordial organism capable of sustainable replication has been successfully developed.

**Roadmap**: The project is guided by the long-term vision of creating a digital universe where complex, open-ended evolution can emerge. Our roadmap is a dynamic process focused on incrementally enhancing the platform's capabilities in three key areas:

* **Fostering Evolutionary Dynamics**: Continuously refining the simulation's parameters and introducing new mechanisms to create an environment that balances stability with creative potential, enabling more complex evolutionary trajectories.
* **Enhancing Scalability and Performance**: Improving the data pipeline and runtime to support ever-larger and longer-running simulations, with a particular focus on enabling massive-scale experiments in a distributed cloud environment.
* **Improving Analysis and Insight**: Developing more sophisticated tools to analyze, visualize, and interpret the vast amounts of data generated, allowing researchers to extract meaningful scientific insights from the simulations.

A live, detailed version of our roadmap, outlining specific tasks and current priorities, is maintained on our [GitHub Project board](https://github.com/users/rainco77/projects/1).

**Get Involved**: We encourage all forms of participation.

- **Live Demo**: A live instance of the visualizer is available at [http://evochora.org/visualizer/](http://evochora.org/visualizer/)
- **Demo Simulation Video**: A video of a primordial organism simulation can be viewed here: [Direct Video Link](https://github-production-user-asset-6210df.s3.amazonaws.com/13830117/518864494-2dd2163a-6abe-4121-936d-eb46cc314859.mp4?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAVCODYLSA53PQK4ZA%2F20251126%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20251126T213314Z&X-Amz-Expires=300&X-Amz-Signature=77a7583f022cdd71315f73f0b4433168fef24a91cc1bc9df6f9f79bb0cd8a45e&X-Amz-SignedHeaders=host)
- **Scientific Discussion**: [GitHub Discussions](https://github.com/rainco77/evochora/discussions)
- **Community Chat**: [Discord Server](https://discord.gg/1442908877648822466)
- **Roadmap**: [GitHub Project board](https://github.com/users/rainco77/projects/1)
- **Source Code**: [GitHub Repository](https://github.com/users/rainco77/evochora)
- **Technical Specifications**: The [Virtual Machine and Assembly Language Specification](ASSEMBLY_SPEC.md) provides a complete technical reference.


The software is open-source and released under the MIT License.

## 7. References

- <a id="ref-ackley-2013"></a>Ackley, D. H. (2013). Indefinite scalability for living computation. In *Artificial Life 13* (pp. 603-610). MIT Press.
- <a id="ref-bedau-2000"></a>Bedau, M. A., Snyder, E., & Packard, N. H. (2000). A classification of long-term evolutionary dynamics. In *Artificial Life VII* (pp. 228-237). MIT Press.
- <a id="ref-bonabeau-1999"></a>Bonabeau, E., Dorigo, M., & Theraulaz, G. (1999). *Swarm Intelligence: From Natural to Artificial Systems*. Oxford University Press.
- <a id="ref-chan-2019"></a>Chan, B. W.-C. (2019). Lenia: Biology of Artificial Life. *Complex Systems*, 28(3), 251-286.
- <a id="ref-dittrich-2001"></a>Dittrich, P., Ziegler, J., & Banzhaf, W. (2001). Artificial chemistries—a review. *Artificial Life*, 7(3), 225-275.
- <a id="ref-eigen-1971"></a>Eigen, M. (1971). Selforganization of matter and the evolution of biological macromolecules. *Naturwissenschaften*, 58(10), 465-523.
- <a id="ref-gebhardt-2019"></a>Gebhardt, G. H., & Polani, D. (2019). The thermodynamic cost of interacting with the environment. In *Artificial Life Conference Proceedings* (pp. 535-542). MIT Press.
- <a id="ref-hamilton-1964"></a>Hamilton, W. D. (1964). The genetical evolution of social behaviour. I. *Journal of Theoretical Biology*, 7(1), 1-16.
- <a id="ref-lane-martin-2010"></a>Lane, N., & Martin, W. (2010). The energetics of genome complexity. *Nature*, 467(7318), 929–934.
- <a id="ref-lynch-2007"></a>Lynch, M. (2007). *The Origins of Genome Architecture*. Sinauer Associates.
- <a id="ref-maynard-smith-1995"></a>Maynard Smith, J., & Szathmáry, E. (1995). *The Major Transitions in Evolution*. Oxford University Press.
- <a id="ref-odling-smee-2003"></a>Odling-Smee, F. J., Laland, K. N., & Feldman, M. W. (2003). *Niche Construction: The Neglected Process in Evolution*. Princeton University Press.
- <a id="ref-ofria-2004"></a>Ofria, C., & Wilke, C. O. (2004). Avida: A software platform for research in digital evolution. *Artificial Life*, 10(2), 191-229.
- <a id="ref-ray-1991"></a>Ray, T. S. (1991). An approach to the synthesis of life. In *Artificial Life II* (pp. 371-408). Addison-Wesley.
- <a id="ref-schrodinger-1944"></a>Schrödinger, E. (1944). *What is Life? The Physical Aspect of the Living Cell*. Cambridge University Press.
- <a id="ref-segre-2001"></a>Segré, D., Ben-Eli, D., Deamer, D. W., & Lancet, D. (2001). The lipid world. *Origins of Life and Evolution of the Biosphere*, 31(1-2), 119-145.
- <a id="ref-sharma-2023"></a>Sharma, A., Czégel, D., Lachmann, M., Kempes, C. P., Walker, S. I., & Cronin, L. (2023). Assembly theory explains and quantifies selection and evolution. *Nature*, 622(7982), 321–328.
- <a id="ref-sims-1994"></a>Sims, K. (1994). Evolving virtual creatures. In *Proceedings of the 21st annual conference on Computer graphics and interactive techniques* (pp. 15-22).
- <a id="ref-soros-2014"></a>Soros, L. B., & Stanley, K. O. (2014). Identifying necessary conditions for open-ended evolution through the artificial life world of Chromaria. In *Artificial Life 14* (pp. 793-800). MIT Press.
- <a id="ref-wagner-2005"></a>Wagner, A. (2005). *Robustness and Evolvability in Living Systems*. Princeton University Press.
- <a id="ref-moreno-2022"></a>Moreno, F., & Ofria, C. (2022). Exploring the Fraternal Transitions to Multicellularity in Digital Evolution. *Artificial Life*, 28(1), 1-24.
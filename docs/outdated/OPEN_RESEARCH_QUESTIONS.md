# Open Research Questions & Call for Collaboration

Evochora is designed not just as a simulation, but as a collaborative scientific instrument. We actively invite researchers, students, and software engineers to use this platform to generate publishable results. Below is a list of concrete, high-impact research avenues that are currently open and directly supported by the architecture.

## 1. Formalizing the Ownership Model
* **Hypothesis:** Strict enforcement of "property rights" (via energy penalties for theft) is a necessary condition for the emergence of stable multicellularity in spatial systems.
* **Task:** Design and implement the `GIFT` instruction protocol and run stability experiments comparing "theft-permitted" vs. "theft-penalized" physics.

## 2. Designing a Minimal Digital Chemistry
* **Hypothesis:** A reaction system with conservation of mass and energy but emitting entropy (thermodynamics) will spontaneously evolve circular metabolic pathways (waste recycling).
* **Task:** Extend Evochora to define a minimal set of `REACTION` rules (e.g., A + B -> C + Energy) and measure the emergence of trophic levels and ecological recycling loops (reminiscent of [Chromaria](https://github.com/evochora/evochora/blob/main/docs/SCIENTIFIC_OVERVIEW.md#ref-soros-2014)).

## 3. Investigating Stability under Fuzzy Addressing
* **Hypothesis:** Fuzzy addressing (SignalGP style) increases the "neutral network" of the fitness landscape, allowing populations to cross fitness valleys that trap rigid-addressing organisms.
* **Task:** Implement a fuzzy jump mechanism and measure the "Time to Error Catastrophe" under high mutation rates compared to standard absolute addressing.

## 4. Measuring Emergent Complexity
* **Goal:** Move beyond visual inspection to rigorous, mathematical quantification of evolution.
* **Task:** Implement a pipeline to calculate **Assembly Theory** indices or LZ-Complexity on the persisted genomes of a long-running simulation to mathematically prove the increase of non-trivial information.

---

### Target Venues for Publication

Results generated with Evochora align well with the research scope of the following communities and conferences:
* **ALife** (Conference on Artificial Life)
* **ECAL** (European Conference on Artificial Life)
* **GECCO** (Genetic and Evolutionary Computation Conference)
* **Artificial Intelligence Journal**
* **Entropy** (Journal)

### Join the Lab
We treat this repository as a distributed research lab. If you are interested in tackling one of these questions, please open a thread in our [GitHub Discussions](https://github.com/evochora/evochora/discussions) tagged `[Research Proposal]` so we can support your experimental setup.
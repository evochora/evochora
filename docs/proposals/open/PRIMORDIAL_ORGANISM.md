# Blueprint for the Primordial Organism in Evochora

This document outlines the hierarchical programming architecture for our primordial organism. The structure is divided into five distinct levels, from high-level control down to atomic, reusable functions.

---

### Level 1: Main Program (`organism.s`)

* **Purpose:** The State Machine 🤖. A minimal code block whose sole responsibility is to check the global state (e.g., current energy level) and then jump to one of the subordinate behavior blocks. It acts as the **conductor** of the organism.

---

### Level 2: Behaviors (`behaviors.s`)

* **Purpose:** Large, self-contained blocks of behavior. A behavior is activated by the main program and runs in a short loop before returning control.

* **Examples:**
    * `BEHAVIOR_SEEK_ENERGY`: Continuously executes the energy-seeking strategy.
    * `BEHAVIOR_REPRODUCE`: Coordinates the various strategies required for reproduction (e.g., finding space, copying code).

---

### Level 3: Strategies (`strategies.s`)

* **Purpose:** The concrete, long-term algorithms used by a behavior. They define **how** a task is accomplished.

* **Examples:**
    * `STRATEGY_EXPLORE_AND_HARVEST`: The plan for finding food (e.g., harvest adjacent cells, then take one step).
    * `STRATEGY_REPRODUCE`: The interruptible algorithm for the code-copying process.

---

### Level 4: Tactics (`tactics.s`)

* **Purpose:** Short, goal-oriented actions used by strategies like tools 🛠️. They combine several `stdlib` functions into a single, meaningful action.

* **Examples:**
    * `TACTIC_HARVEST_SURROUNDINGS`: Scans all neighboring cells and harvests any energy found.
    * `TACTIC_STEP_TO_PASSABLE_NEIGHBOR`: Finds a random, passable neighboring cell and moves the organism to it.

---

### Level 5: Stdlib (`stdlib.s`)

* **Purpose:** The Foundation 🏗️. This level contains atomic, highly reusable procedures that perform a single, basic task. They are the fundamental building blocks for all more complex actions.

* **Examples:**
    * `PROC_IS_PASSABLE`: Determines if a cell is passable for the organism (i.e., empty or owned by self).
    * `PROC_GET_TYPE`: Returns the type of a cell (e.g., empty, energy, enemy).
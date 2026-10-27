# Architecture & Design Decisions

> "Architectural sophistication serves solely to run digital evolution at scale and to make any tick of it observable"

Evochora is not a typical physics simulation or a simple cellular automaton. It is a simulation platform for digital evolution research.
The architecture is defined by two non-negotiable requirements: the safe execution of self-modifying code (Mutability) and the asynchronous processing of high-frequency telemetry (Observability).

This document outlines the **"Why"** behind the engineering choices.

---

## 1. The Core: Why Custom Assembly (EvoASM)?

**The Alternative:** Using standard scripting languages (like Python or Lua) or compiling agents to native code (C++/CUDA).

**The Decision:**
Use a custom Virtual Machine and a dedicated Assembly Language (EvoASM).

**The Reasoning:**
1.  **Mutability:** In this Artificial Life simulation, code is the genome. It must be capable of handling random mutations (bit-flips, insertions, deletions) at runtime. Standard interpreters or compiled binaries are brittle; they typically crash or throw syntax errors when their bytecode is randomly altered.
2.  **Extensibility:** To manage the growing complexity of the language, the implementation of a multi-phase compiler (Frontend, IR, Backend) is needed. Each language feature registers its own handlers for these phases, so the language grows without changes to the compiler pipeline.

---

## 2. The Language: Why Java?

**The Alternative:** Python (standard for research) or Rust/C++ (standard for high-performance simulations).

**The Decision:**
The core simulation was migrated from Python to **Java**.

**The Reasoning:**
1.  **Refactoring & Type Safety:** Early prototypes in Python became unmanageable as the architecture grew. A strictly typed language to safely refactor the complex interactions within the VM and the compiler pipeline without introducing regression bugs was required.
2.  **Performance Strategy:** While Java has trade-offs compared to native code, modern JIT compilers provide sufficient performance for the current simulation complexity.
3.  **Why not Rust?** Thermodynamic policies, energy sources, mutation operators, death handlers and analytics are plugins that the configuration names by class. Java loads them at runtime, so a plugin is compiled on its own and the platform is not rebuilt.

---

## 3. The Architecture: Distributed Services & Scalability

**The Alternative:** A monolithic application where simulation, data processing, and UI run in a tightly coupled loop.

**The Decision:**
The system evolved from a simple monolith into a **Service-Oriented Architecture** (SOA) with a dedicated Data Pipeline.

**The Evolution & Reasoning:**
1.  **The "I/O Wall":** A tightly coupled first version failed at scale. In dense environments, the simulation generates **megabytes of state data per tick**, accumulating to **terabytes** of telemetry over long evolutionary runs. Even with delta compression applied, writing this synchronously to disk or processing it for the UI stalls the simulation thread.
2.  **Horizontal Scalability:** To manage this throughput, the **"Hot Path"** (Simulation Engine) needed to be decoupled from the **"Cold Path"** (Data Analysis). The simulation dumps raw binary data into a high-throughput buffer, while separate consumer services process, index, and store the data asynchronously.
3.  **Cloud Readiness:** All system resources (Queues, Storage, Databases) are designed as abstract interfaces (e.g., `IInputQueueResource`, `IBatchStorageWrite`). This abstraction allows to seamlessly swap local in-memory implementations with cloud-native services (like SQS, Kafka, or S3), enabling the system to scale horizontally in a cloud environment without changing the core application logic.

---

## 4. The Data Protocol: Why Protocol Buffers?

**The Alternative:** JSON or XML.

**The Decision:**
**Protocol Buffers (Protobuf)** is used for inter-service communication, for storage, and for the environment data the web visualizer receives.

**The Reasoning:**
1.  **Storage Efficiency:** With telemetry data accumulating to terabytes, text-based formats like JSON are wasteful. Protobuf's binary format, combined with our delta-compression strategy, reduces storage requirements and disk I/O significantly.
2.  **Performance:** Binary data is faster to encode and decode than text formats, which leaves more CPU time for the simulation itself.
3.  **One Schema for Backend and Browser:** The `.proto` files define the data. The Java code is generated from them, and the visualizer decodes the same messages in the browser with a Protobuf library, so neither side needs a hand-written parser.

---

## Summary for Contributors

Evochora’s architecture is not complex for the sake of complexity. It is **purpose-built** to solve specific scaling problems inherent to digital evolution research:

* Custom Assembly is necessary for mutable genomes.
* Java provides the refactoring safety for a large codebase.
* Distributed Services are required to handle massive I/O throughput.

**You don't need to understand the whole stack to contribute.**
You are invited to contribute to the parts that interest you — whether it's optimizing the VM, writing new analysis plugins, or designing new EvoASM organisms — without needing to understand the entire infrastructure stack.

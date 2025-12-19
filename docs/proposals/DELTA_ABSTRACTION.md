# Implementation Specification: Abstracted Environment State Projection (v2)

## 1. Executive Summary

This document provides a definitive, highly-detailed technical specification for implementing a delta-based state tracking system for the simulation environment. It is intended as a direct blueprint for implementation, leaving no ambiguity in class design, method logic, or data flow.

The core of the implementation is a new, independent module named `projection`, which provides a generic and reusable library for generating and reconstructing state. This library will be used by the `runtime` module for writing data and the `datapipeline` module for reading and serving data.

---

## 2. Module & Package Structure

The following directory and file structure **must** be created.

```
src/main/java/org/evochora/
├── projection/                     // NEW: Independent Core Module
│   ├── api/
│   │   ├── IStateReader.java         // Interface for a readable source state
│   │   ├── IPayloadProvider.java   // Interface for a source of stored payloads
│   │   └── IReconstructedState.java  // Interface for a read-only reconstructed state view
│   ├── dto/
│   │   ├── StateFragment.java      // DTO: A single key-value piece of state (address, data)
│   │   ├── StateSnapshot.java      // DTO: A full state snapshot
│   │   └── StateDelta.java         // DTO: A state change (delta)
│   ├── gen/
│   │   └── PayloadGenerator.java   // Write-side logic for creating payloads
│   └── proj/
│       ├── StateProjector.java     // Read-side logic for reconstructing state
│       └── StateNotFoundException.java // Custom exception for the projector
│
├── runtime/                        // Depends on `projection`
│   └── projection/
│       └── EnvironmentStateAdapter.java // Adapter from `runtime.Environment` to `IStateReader`
│
└── datapipeline/                   // Depends on `projection`
    ├── contracts/
    │   └── EnvironmentPayload.proto   // NEW: Protobuf definitions
    └── provider/
        ├── DatabasePayloadProvider.java // `IPayloadProvider` for the Database
        └── StoragePayloadProvider.java  // `IPayloadProvider` for Raw Storage
```

---

## 3. Core Module: `org.evochora.projection`

### 3.1. `projection.dto` - Data Transfer Objects

These files **must** be created exactly as defined.

**`StateFragment.java`**
```java
package org.evochora.projection.dto;

/**
 * Represents a single addressable component of a state. This is a generic, context-free
 * representation of a key-value pair, where the key is a long address and the value is
 * its serialized byte data.
 *
 * @param address A unique identifier for this fragment within the state (e.g., a flat array index).
 * @param data The serialized byte representation of the fragment's value.
 */
public record StateFragment(long address, byte[] data) {}
```

**`StateSnapshot.java`**
```java
package org.evochora.projection.dto;

import java.util.List;

/**
 * Represents the complete state at a specific point in time (tick). It consists
 * of a collection of all StateFragments that constitute the full state.
 *
 * @param tickNumber The simulation tick this snapshot corresponds to.
 * @param fragments A list of all fragments making up the state.
 */
public record StateSnapshot(long tickNumber, List<StateFragment> fragments) {}
```

**`StateDelta.java`**
```java
package org.evochora.projection.dto;

import java.util.List;

/**
 * Represents the changes to a state relative to a base tick. This can be used for both
 * incremental (tick N-1 -> N) and cumulative (tick Keyframe -> N) deltas.
 *
 * @param tickNumber The simulation tick this delta corresponds to.
 * @param baseTickNumber The tick number of the state this delta should be applied to.
 * @param changedFragments A list of fragments that have been added or have changed since the base tick.
 */
public record StateDelta(long tickNumber, long baseTickNumber, List<StateFragment> changedFragments) {}
```

### 3.2. `projection.api` - Core Interfaces

**`IStateReader.java`**
```java
package org.evochora.projection.api;

import org.evochora.projection.dto.StateSnapshot;
import java.util.Map;

public interface IStateReader {
    /**
     * Creates a full snapshot of the current state. This is a potentially expensive
     * operation that should only be called for keyframes.
     * @param tickNumber The tick number to assign to the snapshot.
     * @return A complete representation of the current state.
     */
    StateSnapshot createSnapshot(long tickNumber);

    /**
     * Retrieves all tracked changes since the last time this method was called,
     * and clears the internal change buffer. This is the core of the efficient,
     * active change tracking mechanism.
     * @return A map of address to its new serialized data. Returns an empty map if no changes occurred.
     */
    Map<Long, byte[]> drainChanges();
}
```

**`IPayloadProvider.java`**
```java
package org.evochora.projection.api;

import org.evochora.projection.dto.StateSnapshot;
import org.evochora.projection.dto.StateDelta;
import java.util.List;
import java.util.Optional;

public interface IPayloadProvider {
    /** Finds the most recent Snapshot payload at or before the given tick. */
    Optional<StateSnapshot> findLatestSnapshot(long tickNumber);
    /** Finds the most recent Cumulative Delta payload strictly after a base tick and at or before a target tick. */
    Optional<StateDelta> findLatestCumulativeDelta(long startTickExclusive, long endTickInclusive);
    /** Finds all Incremental Delta payloads within a strict tick range, sorted by tick number ascending. */
    List<StateDelta> findIncrementalDeltas(long startTickExclusive, long endTickInclusive);
}
```

**`IReconstructedState.java`**
```java
package org.evochora.projection.api;

import java.util.Optional;
import java.util.Set;

public interface IReconstructedState {
    /** Returns the raw byte data for a given address, if it exists in the reconstructed state. */
    Optional<byte[]> getFragmentData(long address);
    /** Returns the set of all addresses (keys) present in the reconstructed state. */
    Set<Long> getAddresses();
    /** Returns the tick number this reconstructed state represents. */
    long getTickNumber();
}
```

### 3.3. `projection.gen.PayloadGenerator` - Write-Side Logic

This is a stateful class that **must** be instantiated once per simulation run.

```java
package org.evochora.projection.gen;
// All required imports...

public final class PayloadGenerator<P> {
    private final IStateReader stateReader;
    private final IPayloadFactory<P> payloadFactory;
    private final long deltaInterval;
    private final long keyframeInterval;
    private final long cumulativeDeltaInterval;

    // State
    private final Map<Long, byte[]> cumulativeChanges = new HashMap<>();
    private final Map<Long, byte[]> incrementalChanges = new HashMap<>();
    private long lastKeyframeTick = 0;

    public PayloadGenerator(Config config, IStateReader stateReader, IPayloadFactory<P> payloadFactory) {
        // ... constructor logic ...
    }

    public Optional<P> generate(long tickNumber) {
        // Step 1: Drain changes from the source. This MUST happen every tick to clear the buffer.
        Map<Long, byte[]> newChanges = stateReader.drainChanges();

        // Step 2: Check if this tick should be recorded. If not, discard changes and return.
        if (tickNumber % deltaInterval != 0) {
            return Optional.empty();
        }
        
        // Step 3: Accumulate the drained changes for the current intervals.
        cumulativeChanges.putAll(newChanges);
        incrementalChanges.putAll(newChanges);

        // Step 4: Determine payload type based on interval rules.
        // A keyframe takes precedence over a cumulative delta.
        if (tickNumber % keyframeInterval == 0) {
            StateSnapshot snapshot = stateReader.createSnapshot(tickNumber);
            P payload = payloadFactory.createSnapshotPayload(snapshot);
            
            // Reset all state after a keyframe.
            lastKeyframeTick = tickNumber;
            cumulativeChanges.clear();
            incrementalChanges.clear();
            
            return Optional.of(payload);
        }

        if (tickNumber % cumulativeDeltaInterval == 0) {
            List<StateFragment> fragments = mapToList(cumulativeChanges);
            StateDelta delta = new StateDelta(tickNumber, lastKeyframeTick, fragments);
            P payload = payloadFactory.createCumulativeDeltaPayload(delta);
            
            // Incremental changes are reset, but cumulative changes persist until the next keyframe.
            incrementalChanges.clear();
            
            return Optional.of(payload);
        }

        // Default case: generate an incremental delta.
        // Determine the base tick for the incremental delta. It's the last recorded tick.
        long lastRecordedTick = Math.max(
            (tickNumber / cumulativeDeltaInterval) * cumulativeDeltaInterval,
            lastKeyframeTick
        );
        long baseTick = (tickNumber - deltaInterval < lastRecordedTick) ? lastRecordedTick : tickNumber - deltaInterval;

        List<StateFragment> fragments = mapToList(incrementalChanges);
        StateDelta delta = new StateDelta(tickNumber, baseTick, fragments);
        P payload = payloadFactory.createIncrementalDeltaPayload(delta);

        incrementalChanges.clear();

        return Optional.of(payload);
    }
    
    private List<StateFragment> mapToList(Map<Long, byte[]> map) {
        return map.entrySet().stream()
            .map(e -> new StateFragment(e.getKey(), e.getValue()))
            .collect(Collectors.toList());
    }

    // This factory is the bridge between the generic DTOs and the specific Protobuf messages.
    // It will be implemented in the `datapipeline` module.
    public interface IPayloadFactory<P> {
        P createSnapshotPayload(StateSnapshot snapshot);
        P createCumulativeDeltaPayload(StateDelta delta);
        P createIncrementalDeltaPayload(StateDelta delta);
    }
}
```

### 3.4. `projection.proj.StateProjector` - Read-Side Logic

This is a stateless class that can be instantiated on-demand.

```java
package org.evochora.projection.proj;
// All required imports...

public final class StateProjector {
    private final IPayloadProvider payloadProvider;
    public StateProjector(IPayloadProvider payloadProvider) { this.payloadProvider = payloadProvider; }

    public IReconstructedState projectState(long tickNumber) throws StateNotFoundException {
        // Step 1: Find the foundational keyframe snapshot. This is mandatory.
        StateSnapshot snapshot = payloadProvider.findLatestSnapshot(tickNumber)
            .orElseThrow(() -> new StateNotFoundException("Cannot reconstruct state: No snapshot found at or before tick " + tickNumber));
        
        // Step 2: Initialize the state with the snapshot's fragments. Use a HashMap for efficient updates.
        final Map<Long, byte[]> stateMap = new HashMap<>();
        snapshot.fragments().forEach(f -> stateMap.put(f.address(), f.data()));
        
        long baseTick = snapshot.tickNumber();

        // Step 3: Find the latest CUMULATIVE delta between the snapshot and the target tick.
        Optional<StateDelta> cumulativeDeltaOpt = payloadProvider.findLatestCumulativeDelta(baseTick, tickNumber);
        
        if (cumulativeDeltaOpt.isPresent()) {
            StateDelta cumulativeDelta = cumulativeDeltaOpt.get();
            cumulativeDelta.changedFragments().forEach(f -> stateMap.put(f.address(), f.data()));
            baseTick = cumulativeDelta.tickNumber();
        }

        // Step 4: Find all INCREMENTAL deltas between the new base tick and the target tick.
        List<StateDelta> incrementalDeltas = payloadProvider.findIncrementalDeltas(baseTick, tickNumber);
        
        // Step 5: Apply each incremental delta in sequence.
        for (StateDelta delta : incrementalDeltas) {
            delta.changedFragments().forEach(f -> stateMap.put(f.address(), f.data()));
        }

        // Step 6: Return a read-only, memory-efficient view of the final reconstructed state.
        return new ReconstructedStateView(tickNumber, stateMap);
    }
    
    private static class ReconstructedStateView implements IReconstructedState {
        private final long tickNumber;
        private final Map<Long, byte[]> state;

        public ReconstructedStateView(long tickNumber, Map<Long, byte[]> state) {
            this.tickNumber = tickNumber;
            this.state = state;
        }

        @Override
        public Optional<byte[]> getFragmentData(long address) {
            return Optional.ofNullable(state.get(address));
        }

        @Override
        public Set<Long> getAddresses() {
            return Collections.unmodifiableSet(state.keySet());
        }

        @Override
        public long getTickNumber() {
            return tickNumber;
        }
    }
}
```
**`StateNotFoundException.java`**
```java
package org.evochora.projection.proj;
public class StateNotFoundException extends Exception {
    public StateNotFoundException(String message) { super(message); }
}
```

---

## 4. `runtime` Module Integration

### 4.1. `runtime.model.Environment` Modification
The class will be modified for active change tracking.

```java
// In: src/main/java/org/evochora/runtime/model/Environment.java
// Add a new private field:
private final Map<Integer, Integer> changedCells = new HashMap<>(); // flatIndex -> packedMoleculeInt

// Modify setMolecule(Molecule molecule, int ownerId, int... coord)
// At the end of the method, add:
this.changedCells.put(index, packed);

// Add the new public method for the adapter
/**
 * Drains the tracked changes for consumption by the projection module.
 * Converts the specific internal format to the generic format required by IStateReader.
 * @return Map of address (long) to serialized data (byte[]).
 */
public Map<Long, byte[]> drainChanges() {
    if (changedCells.isEmpty()) {
        return Collections.emptyMap();
    }
    
    Map<Long, byte[]> drained = new HashMap<>();
    ByteBuffer buffer = ByteBuffer.allocate(4);
    for (Map.Entry<Integer, Integer> entry : changedCells.entrySet()) {
        // Key: convert int to long. Value: convert int to byte[4].
        buffer.clear();
        drained.put(entry.getKey().longValue(), buffer.putInt(entry.getValue()).array());
    }
    
    changedCells.clear();
    return drained;
}
```

### 4.2. `runtime.projection.EnvironmentStateAdapter`
This new class adapts `Environment` to the `IStateReader` interface.

```java
// In: src/main/java/org/evochora/runtime/projection/EnvironmentStateAdapter.java
package org.evochora.runtime.projection;
// imports...

public class EnvironmentStateAdapter implements IStateReader {
    private final Environment environment;
    private final ByteBuffer buffer = ByteBuffer.allocate(4);

    public EnvironmentStateAdapter(Environment environment) {
        this.environment = environment;
    }

    @Override
    public StateSnapshot createSnapshot(long tickNumber) {
        List<StateFragment> fragments = new ArrayList<>();
        environment.forEachOccupiedIndex(flatIndex -> {
            int moleculeInt = environment.getMoleculeInt(flatIndex);
            buffer.clear();
            byte[] data = buffer.putInt(moleculeInt).array();
            fragments.add(new StateFragment((long) flatIndex, data));
        });
        return new StateSnapshot(tickNumber, fragments);
    }

    @Override
    public Map<Long, byte[]> drainChanges() {
        return environment.drainChanges();
    }
}
```

---

## 5. `datapipeline` Module Integration

### 5.1. `datapipeline.contracts.EnvironmentPayload.proto`
This new file defines the on-disk format.

```protobuf
// In: src/main/proto/EnvironmentPayload.proto
syntax = "proto3";
package org.evochora.datapipeline.contracts;
import "TickData.proto"; // For CellDataColumns

message EnvironmentSnapshot {
    int64 tick_number = 1;
    CellDataColumns all_cells = 2;
}
// ... other message definitions as in the previous version ...
message EnvironmentPayload { /* ... */ }
```

### 5.2. `datapipeline.provider.DatabasePayloadProvider`
This class adapts the database (which stores Protobuf) to the `IPayloadProvider` interface (which uses DTOs).

```java
// In: src/main/java/org/evochora/datapipeline/provider/DatabasePayloadProvider.java
// ...
public class DatabasePayloadProvider implements IPayloadProvider {
    // ... dbReader dependency ...
    @Override
    public Optional<StateSnapshot> findLatestSnapshot(long tickNumber) {
        // 1. dbReader.findLatestSnapshotBlob(tickNumber) -> Optional<EnvironmentSnapshot>
        // 2. If present, convert it to the generic DTO.
        return optionalProto.map(this::toGenericSnapshot);
    }
    
    private StateSnapshot toGenericSnapshot(EnvironmentSnapshot proto) {
        // Logic to iterate over proto.getAllCells() (CellDataColumns)
        // and create a List<StateFragment>.
    }
    // ... similar implementations for findLatestCumulativeDelta and findIncrementalDeltas ...
}
```

### 5.3. `PersistenceService` Modification
The `PersistenceService` input queue type will change from `TickData` to `EnvironmentPayload`. Its logic remains otherwise identical, as it treats the payload as an opaque blob to be written to storage.

---

## 6. Configuration & Final Integration

The HOCON configuration and Dependency Injection setup will proceed as described in the previous version of this document. The `SimulationEngine` will instantiate the `EnvironmentStateAdapter` and the `PayloadGenerator`, orchestrating the write path. The `EnvironmentController`, CLI tools, and `AnalyticsIndexer` will instantiate the appropriate `IPayloadProvider` and use the `StateProjector` to reconstruct state on the read path.
```
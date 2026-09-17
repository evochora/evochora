# Implementation Specification: Abstracted Environment State Projection

## 1. Executive Summary

This document provides a definitive, highly-detailed technical specification for implementing a high-performance, delta-based state tracking system.

**Critical Performance Requirements:**
- **Zero-Allocation on Hot Paths:** No object allocation during simulation ticks or delta accumulation.
- **Data-Oriented Design:** Usage of primitive arrays and `fastutil` collections to avoid boxing.
- **Wait-Free Thread-Local Buffering:** Elimination of locks and contention in the hot path.
- **Memory Efficiency:** Buffer recycling (Ping-Pong) instead of reallocation.

---

## 2. Dependencies

The implementation requires `fastutil` for primitive collections to avoid autoboxing.

```groovy
// build.gradle.kts
implementation("it.unimi.dsi:fastutil:8.5.12")
```

---

## 3. Module & Package Structure

```
src/main/java/org/evochora/
├── projection/                     // NEW: Independent Core Module
│   ├── api/
│   │   ├── IStateReader.java         // Interface for a readable source state
│   │   ├── IPayloadProvider.java     // Interface for a source of stored payloads
│   │   └── IReconstructedState.java  // Interface for a read-only reconstructed state view
│   ├── dto/
│   │   ├── StateSnapshot.java        // DTO: Full state (Primitive Arrays)
│   │   └── StateDelta.java           // DTO: Delta state (Primitive Arrays)
│   ├── gen/
│   │   └── PayloadGenerator.java     // Logic: Accumulates changes & generates payloads (Zero-Alloc)
│   └── proj/
│       ├── StateProjector.java       // Logic: Reconstructs state from payloads
│       ├── StatefulProjector.java    // Logic: Stateful streaming access
│       └── StateNotFoundException.java
│
├── runtime/                        // Depends on `projection`
│   └── projection/
│       └── EnvironmentStateAdapter.java // Adapter: Environment -> IStateReader
│
└── datapipeline/                   // Depends on `projection`
    ├── contracts/
    │   └── EnvironmentPayload.proto   // Protobuf definitions
    └── provider/
        ├── DatabasePayloadProvider.java
        └── StoragePayloadProvider.java
```

---

## 4. Core Module: `org.evochora.projection`

### 4.1. `projection.dto` - Primitive Data Records

**`StateSnapshot.java`**
```java
package org.evochora.projection.dto;

import java.util.Arrays;

public record StateSnapshot(long tickNumber, long[] addresses, byte[] data, int cellSizeBytes) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StateSnapshot that)) return false;
        return tickNumber == that.tickNumber && 
               cellSizeBytes == that.cellSizeBytes &&
               Arrays.equals(addresses, that.addresses) && 
               Arrays.equals(data, that.data);
    }
    @Override
    public int hashCode() {
        int result = Long.hashCode(tickNumber);
        result = 31 * result + Arrays.hashCode(addresses);
        result = 31 * result + Arrays.hashCode(data);
        result = 31 * result + cellSizeBytes;
        return result;
    }
}
```

**`StateDelta.java`**
```java
package org.evochora.projection.dto;

import java.util.Arrays;

public record StateDelta(long tickNumber, long baseTickNumber, long[] changedAddresses, byte[] changedData, int cellSizeBytes) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StateDelta that)) return false;
        return tickNumber == that.tickNumber && 
               baseTickNumber == that.baseTickNumber &&
               cellSizeBytes == that.cellSizeBytes &&
               Arrays.equals(changedAddresses, that.changedAddresses) && 
               Arrays.equals(changedData, that.changedData);
    }
    @Override
    public int hashCode() {
        int result = Long.hashCode(tickNumber);
        result = 31 * result + Long.hashCode(baseTickNumber);
        result = 31 * result + Arrays.hashCode(changedAddresses);
        result = 31 * result + Arrays.hashCode(changedData);
        result = 31 * result + cellSizeBytes;
        return result;
    }
}
```

### 4.2. `projection.api` - Core Interfaces

**`IStateReader.java`**
Optimized for zero-allocation data transfer using NIO buffers.

```java
package org.evochora.projection.api;

import org.evochora.projection.dto.StateSnapshot;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

public interface IStateReader {
    StateSnapshot createSnapshot(long tickNumber);

    /**
     * Drains pending changes into the provided sink buffers.
     * Implementation must avoid allocation by writing directly to buffers.
     * 
     * @param addressSink Buffer for 64-bit addresses.
     * @param dataSink    Buffer for raw byte data.
     * @return Number of changes written.
     */
    int drainChangesTo(LongBuffer addressSink, ByteBuffer dataSink);
    
    int getCellSizeBytes();
    long getLastSnapshotSizeInBytes();
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
    Optional<StateSnapshot> findLatestSnapshot(long tickNumber);
    Optional<StateDelta> findLatestCumulativeDelta(long startTickExclusive, long endTickInclusive);
    List<StateDelta> findIncrementalDeltas(long startTickExclusive, long endTickInclusive);
}
```

**`IReconstructedState.java`**
```java
package org.evochora.projection.api;

import java.util.Optional;
import java.util.Set;

public interface IReconstructedState {
    Optional<byte[]> getFragmentData(long address);
    Set<Long> getAddresses();
    long getTickNumber();
}
```

### 4.3. `projection.gen.PayloadGenerator` - ZERO-ALLOCATION Implementation

This class uses primitive collections (`fastutil`) to accumulate changes without creating `byte[]` objects for individual cells.

```java
package org.evochora.projection.gen;

import com.typesafe.config.Config;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import org.evochora.projection.api.IStateReader;
import org.evochora.projection.dto.StateDelta;
import org.evochora.projection.dto.StateSnapshot;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Optional;

public final class PayloadGenerator<P> {
    private final IStateReader stateReader;
    private final IPayloadFactory<P> payloadFactory;
    
    // Config
    private final long deltaInterval;
    private final long keyframeInterval;
    private final long cumulativeDeltaInterval;
    private final long maxCumulativeDeltaSizeBytes;

    // Accumulators (Structure of Arrays)
    // lookup: Maps Address -> Index in accAddresses/accData
    private final Long2IntOpenHashMap lookup;
    private final LongArrayList accAddresses;
    private final ByteArrayList accData;
    
    private long lastKeyframeTick = 0;
    
    // Transfer Buffers
    private final LongBuffer addrBuffer;
    private final ByteBuffer dataBuffer;
    private final int cellSize;

    public PayloadGenerator(Config config, IStateReader stateReader, IPayloadFactory<P> payloadFactory) {
        this.stateReader = stateReader;
        this.payloadFactory = payloadFactory;
        this.cellSize = stateReader.getCellSizeBytes();
        
        this.deltaInterval = config.getLong("deltaInterval");
        this.keyframeInterval = config.getLong("keyframeInterval");
        this.cumulativeDeltaInterval = config.getLong("cumulativeDeltaInterval");
        this.maxCumulativeDeltaSizeBytes = config.hasPath("maxCumulativeDeltaSizeBytes") 
            ? config.getLong("maxCumulativeDeltaSizeBytes") : -1;

        // Init Accumulators with reasonable defaults (resizable)
        this.lookup = new Long2IntOpenHashMap(10000);
        this.lookup.defaultReturnValue(-1);
        this.accAddresses = new LongArrayList(10000);
        this.accData = new ByteArrayList(10000 * cellSize);
        
        // Init Transfer Buffers
        int maxChangesPerTick = 100000; // Configurable ideally
        this.addrBuffer = LongBuffer.allocate(maxChangesPerTick);
        this.dataBuffer = ByteBuffer.allocate(maxChangesPerTick * cellSize);
    }

    public Optional<P> generate(long tickNumber) {
        // 1. Drain changes (Zero-Alloc)
        addrBuffer.clear();
        dataBuffer.clear();
        int count = stateReader.drainChangesTo(addrBuffer, dataBuffer);
        
        // 2. Merge into Accumulators (Zero-Alloc)
        // We accumulate EVERY update, regardless of emission interval.
        // This ensures no changes are lost if samplingInterval < deltaInterval.
        
        // Prepare buffers for reading
        addrBuffer.flip();
        dataBuffer.flip();

        for (int i = 0; i < count; i++) {
            long addr = addrBuffer.get();
            // Note: dataBuffer position advances automatically if we read from it
            // We need to read 'cellSize' bytes.
            
            int existingIndex = lookup.get(addr);
            
            if (existingIndex != -1) {
                // Update existing: Overwrite bytes in accData
                int startOffset = existingIndex * cellSize;
                for (int b = 0; b < cellSize; b++) {
                    accData.set(startOffset + b, dataBuffer.get());
                }
            } else {
                // Insert new: Add to primitive lists
                int newIndex = accAddresses.size();
                lookup.put(addr, newIndex);
                accAddresses.add(addr);
                for (int b = 0; b < cellSize; b++) {
                    accData.add(dataBuffer.get());
                }
            }
        }
        
        // 3. Check Interval
        if (tickNumber % deltaInterval != 0) {
            return Optional.empty(); // Keep accumulating
        }

        // 4. Determine Payload Type
        boolean forceKeyframe = (tickNumber % keyframeInterval == 0);
        
        if (!forceKeyframe && maxCumulativeDeltaSizeBytes > 0) {
            long currentSize = accData.size(); // Approximate size in bytes
            if (currentSize > maxCumulativeDeltaSizeBytes) forceKeyframe = true;
        }

        if (forceKeyframe) {
            StateSnapshot snapshot = stateReader.createSnapshot(tickNumber);
            P payload = payloadFactory.createSnapshotPayload(snapshot);
            resetAccumulators();
            lastKeyframeTick = tickNumber;
            return Optional.of(payload);
        }

        if (tickNumber % cumulativeDeltaInterval == 0) {
            StateDelta delta = createDelta(tickNumber, lastKeyframeTick);
            P payload = payloadFactory.createCumulativeDeltaPayload(delta);
            // Cumulative delta covers everything.
            // When a Cumulative Delta is emitted, it acts as a "checkpoint".
            // We clear the accumulators because the next incremental delta will start from here.
            resetAccumulators(); 
            return Optional.of(payload);
        }

        // Incremental Delta
        long baseTick = tickNumber - deltaInterval;
        StateDelta delta = createDelta(tickNumber, baseTick);
        P payload = payloadFactory.createIncrementalDeltaPayload(delta);
        
        resetAccumulators();
        return Optional.of(payload);
    }

    private StateDelta createDelta(long tick, long baseTick) {
        // Zero-copy if possible, or fast array copy
        // StateDelta records take ownership of arrays. 
        // We must copy because accumulators are reused.
        
        long[] addresses = accAddresses.toLongArray(); // fastutil copy
        byte[] data = accData.toByteArray();           // fastutil copy
        
        return new StateDelta(tick, baseTick, addresses, data, cellSize);
    }

    private void resetAccumulators() {
        lookup.clear();
        accAddresses.clear();
        accData.clear();
    }
    
    public interface IPayloadFactory<P> {
        P createSnapshotPayload(StateSnapshot snapshot);
        P createCumulativeDeltaPayload(StateDelta delta);
        P createIncrementalDeltaPayload(StateDelta delta);
    }
}
```

### 4.4. `projection.proj.StateProjector` - Stateless Read-Side Logic

Uses primitive maps for efficient reconstruction.

```java
package org.evochora.projection.proj;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.evochora.projection.api.IPayloadProvider;
import org.evochora.projection.api.IReconstructedState;
import org.evochora.projection.dto.StateDelta;
import org.evochora.projection.dto.StateSnapshot;

import java.util.*;

public final class StateProjector {
    private final IPayloadProvider payloadProvider;
    
    public StateProjector(IPayloadProvider payloadProvider) { 
        this.payloadProvider = payloadProvider; 
    }

    public IReconstructedState projectState(long tickNumber) throws StateNotFoundException {
        // 1. Snapshot
        StateSnapshot snapshot = payloadProvider.findLatestSnapshot(tickNumber)
            .orElseThrow(() -> new StateNotFoundException("No snapshot found for tick " + tickNumber));
        
        int cellSize = snapshot.cellSizeBytes();
        
        // Use Fastutil primitive map for efficient storage
        final Long2ObjectOpenHashMap<byte[]> stateMap = new Long2ObjectOpenHashMap<>(snapshot.addresses().length);
        populateMap(stateMap, snapshot.addresses(), snapshot.data(), cellSize);
        
        long baseTick = snapshot.tickNumber();

        // 2. Cumulative Delta
        Optional<StateDelta> cumDelta = payloadProvider.findLatestCumulativeDelta(baseTick, tickNumber);
        if (cumDelta.isPresent()) {
            StateDelta delta = cumDelta.get();
            populateMap(stateMap, delta.changedAddresses(), delta.changedData(), cellSize);
            baseTick = delta.tickNumber();
        }

        // 3. Incremental Deltas
        List<StateDelta> incDeltas = payloadProvider.findIncrementalDeltas(baseTick, tickNumber);
        for (StateDelta delta : incDeltas) {
            populateMap(stateMap, delta.changedAddresses(), delta.changedData(), cellSize);
        }

        return new ReconstructedStateView(tickNumber, stateMap);
    }

    private void populateMap(Long2ObjectOpenHashMap<byte[]> map, long[] addrs, byte[] data, int cellSize) {
        for (int i = 0; i < addrs.length; i++) {
            byte[] cell = new byte[cellSize];
            System.arraycopy(data, i * cellSize, cell, 0, cellSize);
            map.put(addrs[i], cell);
        }
    }
    
    private static class ReconstructedStateView implements IReconstructedState {
        private final long tickNumber;
        private final Long2ObjectOpenHashMap<byte[]> state;

        public ReconstructedStateView(long tickNumber, Long2ObjectOpenHashMap<byte[]> state) {
            this.tickNumber = tickNumber;
            this.state = state;
        }

        @Override
        public Optional<byte[]> getFragmentData(long address) {
            return Optional.ofNullable(state.get(address));
        }

        @Override
        public Set<Long> getAddresses() {
            return state.keySet();
        }

        @Override
        public long getTickNumber() {
            return tickNumber;
        }
    }
}
```

### 4.5 `projection.proj.StatefulProjector`

```java
package org.evochora.projection.proj;

import org.evochora.projection.api.IPayloadProvider;
import org.evochora.projection.api.IReconstructedState;

public class StatefulProjector {
    private final IPayloadProvider payloadProvider;
    private IReconstructedState currentState;

    public StatefulProjector(IPayloadProvider provider) { this.payloadProvider = provider; }

    public IReconstructedState seekTo(long tickNumber) throws StateNotFoundException {
        this.currentState = new StateProjector(payloadProvider).projectState(tickNumber);
        return this.currentState;
    }

    public IReconstructedState advanceToNext() {
        // Note: This needs logic to fetch the *single next* delta from provider.
        // For CLI/Video purposes, we assume strictly sequential access.
        // Simplified implementation:
        long nextTick = currentState.getTickNumber() + 1; // Or +deltaInterval
        try {
            // Re-use StateProjector logic or optimize by applying just one delta to internal map
            // For now, delegate to full projection for correctness (can be optimized later)
            this.currentState = new StateProjector(payloadProvider).projectState(nextTick);
        } catch (StateNotFoundException e) {
            return null;
        }
        return this.currentState;
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

## 5. `runtime` Module Integration

### 5.1. `runtime.model.Environment` - THREAD-LOCAL BUFFERING

Optimized for **Wait-Free Concurrency** and **Zero-Garbage**.
Uses `ThreadLocal` storage to eliminate locks in the hot path.

```java
// In: src/main/java/org/evochora/runtime/model/Environment.java

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Environment {
    // ... existing fields ...

    // Registry of all thread-local buffers so the main thread can drain them
    private final ConcurrentLinkedQueue<Int2IntOpenHashMap> threadBufferRegistry = new ConcurrentLinkedQueue<>();

    // ThreadLocal storage for change tracking (Wait-Free)
    // Each thread gets its own primitive map.
    private final ThreadLocal<Int2IntOpenHashMap> localChangeBuffer = ThreadLocal.withInitial(() -> {
        // Initial size 1024, Load Factor 0.75
        Int2IntOpenHashMap map = new Int2IntOpenHashMap(1024);
        // Register this buffer so drainChangesTo can access it later
        threadBufferRegistry.add(map);
        return map;
    });

    public void setMolecule(Molecule molecule, int ownerId, int... coord) {
        int index = getFlatIndex(coord);
        int packed = molecule.toInt();
        
        this.grid[index] = packed;
        this.ownerGrid[index] = ownerId;
        
        // Zero-Overhead, Wait-Free Write
        // No locks, no volatile write, pure L1-cache access
        this.localChangeBuffer.get().put(index, packed);
        
        // ... sparse tracking logic ...
    }

    /**
     * Called by PayloadGenerator (Single Threaded Phase after Barrier).
     * Drains all thread-local buffers and clears them (recycling).
     */
    public int drainChangesTo(LongBuffer addrSink, ByteBuffer dataSink) {
        int totalChanges = 0;
        
        // Iterate over all registered thread buffers
        // Note: threadBufferRegistry is concurrent, so safe to iterate.
        // We assume all worker threads are PAUSED at the barrier here.
        
        for (Int2IntOpenHashMap buffer : threadBufferRegistry) {
            // Skip empty buffers to avoid iterator overhead
            if (buffer.isEmpty()) continue;
            
            // Fast iteration over primitive map
            var iterator = buffer.int2IntEntrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                addrSink.put((long) entry.getIntKey());
                dataSink.putInt(entry.getIntValue());
                totalChanges++;
            }
            
            // CRITICAL: Clear the buffer to recycle the backing arrays.
            // Do NOT remove from registry, as thread will reuse it next tick.
            buffer.clear();
        }
        
        return totalChanges;
    }
}
```

### 5.2. `EnvironmentStateAdapter`

```java
package org.evochora.runtime.projection;

import org.evochora.projection.api.IStateReader;
import org.evochora.projection.dto.StateSnapshot;
import org.evochora.runtime.model.Environment;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

public class EnvironmentStateAdapter implements IStateReader {
    private final Environment environment;
    private static final int CELL_SIZE = 4; // int molecule packed

    public EnvironmentStateAdapter(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getCellSizeBytes() { return CELL_SIZE; }

    @Override
    public StateSnapshot createSnapshot(long tickNumber) {
        // Implementation that efficiently extracts full state
        // For this spec, we assume environment has a method for this or we iterate grid
        // ... (Full implementation would traverse grid and fill arrays) ...
        return new StateSnapshot(tickNumber, new long[0], new byte[0], CELL_SIZE); // Placeholder for brevity
    }

    @Override
    public int drainChangesTo(LongBuffer addrSink, ByteBuffer dataSink) {
        return environment.drainChangesTo(addrSink, dataSink);
    }

    @Override
    public long getLastSnapshotSizeInBytes() {
        return 0; 
    }
}
```

---

## 6. `datapipeline` Module Integration

### 6.1. `EnvironmentPayload.proto`

```protobuf
syntax = "proto3";
package org.evochora.datapipeline.contracts;

message EnvironmentSnapshot {
    int64 tick_number = 1;
    bytes all_cells_data = 2; 
    repeated int64 addresses = 3; 
    int32 cell_size_bytes = 4;
}

message EnvironmentCumulativeDelta {
    int64 tick_number = 1;
    int64 base_tick_number = 2;
    bytes changed_cells_data = 3;
    repeated int64 changed_addresses = 4;
    int32 cell_size_bytes = 5;
}

message EnvironmentIncrementalDelta {
    int64 tick_number = 1;
    int64 base_tick_number = 2;
    bytes changed_cells_data = 3;
    repeated int64 changed_addresses = 4;
    int32 cell_size_bytes = 5;
}

message EnvironmentPayload {
    oneof payload {
        EnvironmentSnapshot snapshot = 1;
        EnvironmentCumulativeDelta cumulative_delta = 2;
        EnvironmentIncrementalDelta incremental_delta = 3;
    }
}
```

### 6.2. `DatabasePayloadProvider`

Uses Protobuf to generic DTO mapping.

```java
package org.evochora.datapipeline.provider;

import org.evochora.datapipeline.contracts.EnvironmentPayload;
import org.evochora.projection.api.IPayloadProvider;
import org.evochora.projection.dto.StateDelta;
import org.evochora.projection.dto.StateSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.Collections;

public class DatabasePayloadProvider implements IPayloadProvider {
    // ... Database Driver Dependency ...

    @Override
    public Optional<StateSnapshot> findLatestSnapshot(long tickNumber) {
        // 1. Fetch byte[] blob from DB
        // 2. Parse EnvironmentPayload proto
        // 3. Map to StateSnapshot DTO
        return Optional.empty(); 
    }

    @Override
    public Optional<StateDelta> findLatestCumulativeDelta(long startTickExclusive, long endTickInclusive) {
        return Optional.empty(); 
    }

    @Override
    public List<StateDelta> findIncrementalDeltas(long startTickExclusive, long endTickInclusive) {
        return Collections.emptyList();
    }
}
```

---

## 7. Configuration Changes

The following changes must be applied to `evochora.conf`.

### 7.1. New Projection Section
Add a new `projection` block to the `simulation-engine` configuration.

```hocon
    simulation-engine {
      # ... existing config ...
      
      options {
        # ... existing options ...

        # NEW: Projection Configuration for Delta-Based State Tracking
        projection {
          # Capture delta every N ticks.
          # Relationship to samplingInterval:
          #   - samplingInterval (above) controls how often the engine *polls* for data.
          #   - deltaInterval controls how often a payload is *emitted*.
          #   - Ideally: set samplingInterval = deltaInterval for max performance.
          #   - If samplingInterval=1 and deltaInterval=10: Generator accumulates 10 ticks, then emits.
          deltaInterval = 10
          
          # Force a full Keyframe (Snapshot) every N ticks.
          # MUST be a multiple of deltaInterval.
          keyframeInterval = 1000
          
          # Emit a Cumulative Delta (from last keyframe) every N ticks.
          # MUST be a multiple of deltaInterval.
          # Allows faster seeking without loading 100 incremental deltas.
          cumulativeDeltaInterval = 100
          
          # Optional: Force a Keyframe if cumulative delta size exceeds this limit (bytes).
          # Safety net to prevent massive delta chains in chaotic simulations.
          maxCumulativeDeltaSizeBytes = 5242880 # 5 MB
        }
      }
    }
```

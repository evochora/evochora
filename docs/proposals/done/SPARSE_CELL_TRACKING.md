# Sparse Cell Tracking Performance Optimization

## Problem Statement

The current simulation engine serializes all world cells for every tick, causing severe performance degradation in large worlds:

- **10x10 world**: 100 cells per tick
- **100x100 world**: 10,000 cells per tick (100x slower)
- **1000x1000 world**: 1,000,000 cells per tick (10,000x slower)

This bottleneck occurs in `SimulationEngine.toRawState()` which iterates through all cells regardless of whether they contain data.

## Current Implementation

```java
// SimulationEngine.toRawState() - Performance bottleneck
iterateOptimized(shape, 0, coord, () -> {
    var m = env.getMolecule(coord);
    int ownerId = env.getOwnerId(coord);
    if (m.toInt() != 0 || ownerId != 0) {
        cells.add(new RawCellState(coord.clone(), m.toInt(), ownerId));
    }
});
```

**Problem**: Even with "optimized" iteration, all cells are still processed.

## Proposed Solution: Sparse Cell Tracking

### Core Concept

Only serialize cells that are either:
- **Non-empty**: `molecule != CODE:0`
- **Owned**: `ownerId != 0`

Empty cells are implicitly assumed to be `CODE:0, owner:0` by the WebDebugger.

### Architecture Decision

**Option A**: Only RawDB (Sparse)
- RawDB: Only occupied cells
- DebugDB: All cells (reconstructed by DebugIndexer)
- **Problem**: Performance gains lost in DebugIndexer

**Option B**: Through entire pipeline (Sparse) ✅ **CHOSEN**
- RawDB: Only occupied cells
- DebugDB: Only occupied cells  
- WebDebugger: Renders only occupied cells, assumes empty cells are `CODE:0, owner:0`

### Implementation Strategy

**Approach**: Front-to-back implementation (no backward compatibility)
1. SimulationEngine → RawDB (Sparse)
2. DebugIndexer → DebugDB (Sparse)
3. Debug Server → API (Sparse)
4. WebDebugger → Rendering (Sparse)

## Technical Implementation

### 1. Configuration

```java
// Config.java
public static final boolean ENABLE_SPARSE_CELL_TRACKING = true;
```

### 2. Environment Tracking

```java
class Environment {
    private final int[] grid;        // Flat array for performance
    private final int[] ownerGrid;   // Flat array for performance
    private Set<int[]> occupiedCells = new HashSet<>(); // Tracking for serialization
    
    public void setMolecule(int[] coord, Molecule m) {
        if (m.toInt() != 0) {
            occupiedCells.add(coord.clone());
        } else {
            if (getOwnerId(coord) == 0) {
                occupiedCells.remove(coord);
            }
        }
        // ... normal logic
    }
    
    public void setOwnerId(int[] coord, int ownerId) {
        if (ownerId != 0) {
            occupiedCells.add(coord.clone());
        } else {
            if (getMolecule(coord).toInt() == 0) {
                occupiedCells.remove(coord);
            }
        }
        // ... normal logic
    }
    
    public List<RawCellState> getOccupiedCells() {
        if (Config.ENABLE_SPARSE_CELL_TRACKING) {
            return occupiedCells.stream()
                .map(coord -> new RawCellState(coord, getMolecule(coord).toInt(), getOwnerId(coord)))
                .collect(toList());
        } else {
            return getAllCells(); // Fallback
        }
    }
}
```

### 3. SimulationEngine Changes

```java
private RawTickState toRawState(Simulation simulation) {
    final var env = simulation.getEnvironment();
    
    // OLD: Iterate through all cells
    // List<RawCellState> cells = new ArrayList<>();
    // iterateOptimized(shape, 0, coord, () -> { ... });
    
    // NEW: Get only occupied cells
    List<RawCellState> cells = env.getOccupiedCells();
    
    // ... organism processing unchanged
    
    return new RawTickState(simulation.getCurrentTick(), organisms, cells);
}
```

### 4. WebDebugger Compatibility

```javascript
// WebDebugger renders all cells, assumes empty cells are CODE:0, owner:0
for (let x = 0; x < worldWidth; x++) {
    for (let y = 0; y < worldHeight; y++) {
        const cell = sparseCells.find(c => c.x === x && c.y === y);
        if (cell) {
            renderCell(x, y, cell.value, cell.owner);
        } else {
            renderCell(x, y, 0, 0); // Implicit empty
        }
    }
}
```

## Performance Analysis

### Memory Usage

**Tracking Overhead**:
- **2D 1000x1000**: 1M cells × 32 bytes = 32 MB
- **3D 1000x1000x1000**: 1B cells × 36 bytes = 36 GB

**Recommendation**: Use flat arrays for performance + HashSet for tracking (hybrid approach)

### Performance Gains

**Sparse Worlds** (1% occupied):
- **10x10**: 100 → 1 cells (100x faster)
- **100x100**: 10,000 → 100 cells (100x faster)
- **1000x1000**: 1,000,000 → 10,000 cells (100x faster)

**Dense Worlds** (50% occupied):
- **1000x1000**: 1,000,000 → 500,000 cells (2x faster)

### Architecture Trade-offs

**Flat Arrays vs HashMap**:
- **Flat Arrays**: 5-10x faster access, 250x more RAM
- **HashMap**: 5-10x slower access, 250x less RAM

**Chosen**: Hybrid approach (flat arrays + tracking) for optimal balance.

## Implementation Plan

### Phase 1: Environment Tracking
1. Add `occupiedCells` HashSet to Environment
2. Update `setMolecule()` and `setOwnerId()` to maintain tracking
3. Implement `getOccupiedCells()` method

### Phase 2: SimulationEngine
1. Modify `toRawState()` to use `getOccupiedCells()`
2. Remove old iteration logic
3. Add configuration flag

### Phase 3: Pipeline Integration
1. Update DebugIndexer to handle sparse data
2. Update Debug Server API
3. Update WebDebugger rendering

### Phase 4: Testing
1. Unit tests for Environment tracking
2. Integration tests for full pipeline
3. Performance benchmarks

## Expected Results

- **10-10,000x performance improvement** for sparsely populated worlds
- **2-3x performance improvement** for densely populated worlds
- **Minimal memory overhead** (32 MB for 1000x1000 worlds)
- **Full WebDebugger compatibility** with implicit empty cell handling
- **Configurable** via `ENABLE_SPARSE_CELL_TRACKING` flag

## Performance Trade-offs

### Simulation Performance Impact

**Current Implementation (without tracking)**:
```java
public void setMolecule(int[] coord, Molecule m) {
    grid[coord[0] + coord[1] * width + coord[2] * width * height] = m.toInt();
    // Only 1 array access
}
```

**With Tracking**:
```java
public void setMolecule(int[] coord, Molecule m) {
    grid[coord[0] + coord[1] * width + coord[2] * width * height] = m.toInt();
    
    // Additional tracking operations
    if (m.toInt() != 0) {
        occupiedCells.add(coord.clone()); // HashSet.add() + Array.clone()
    } else {
        if (getOwnerId(coord) == 0) {
            occupiedCells.remove(coord); // HashSet.remove()
        }
    }
}
```

**Performance Impact**:
- **Array access**: ~1-2 CPU cycles
- **HashSet operations**: ~10-50 CPU cycles
- **Array.clone()**: ~5-15 CPU cycles
- **Total overhead**: ~20-70 CPU cycles per setMolecule call

**Result**: Simulation becomes 10-35x slower for setMolecule operations, but serialization becomes 10,000x faster.

### Optimization Options

**Option 1: Immediate Tracking (Current Proposal)**
- **Pros**: Simple implementation, always up-to-date
- **Cons**: 10-35x slower simulation performance on setMolecule
- **Use case**: When serialization is the main bottleneck

**Option 2: Lazy Tracking**
```java
private boolean trackingDirty = false;

public void setMolecule(int[] coord, Molecule m) {
    grid[coord[0] + coord[1] * width + coord[2] * width * height] = m.toInt();
    trackingDirty = true; // Only set flag
}

public List<RawCellState> getOccupiedCells() {
    if (trackingDirty) {
        rebuildTracking(); // Rebuild tracking when needed
        trackingDirty = false;
    }
    return occupiedCells.stream()...
}
```
- **Pros**: Minimal simulation overhead, tracking rebuilt only when needed
- **Cons**: Complex implementation, potential memory spikes during rebuild
- **Use case**: When simulation performance is critical

**Option 3: Batch Updates**
```java
public void endTick() {
    rebuildTracking(); // Update tracking after each tick
}
```
- **Pros**: Balanced approach, predictable performance
- **Cons**: Tracking may be stale during tick execution
- **Use case**: When tick-based updates are acceptable

**Recommendation**: Start with Option 1 (immediate tracking) for simplicity, optimize to Option 2 if simulation performance becomes critical.

## Risks and Mitigation

**Risk**: Breaking changes in pipeline
**Mitigation**: Comprehensive testing, configurable flag for fallback

**Risk**: Memory overhead for large worlds
**Mitigation**: Hybrid approach with flat arrays for performance

**Risk**: WebDebugger compatibility issues
**Mitigation**: Implicit empty cell handling, no API changes needed

**Risk**: Simulation performance degradation
**Mitigation**: Configurable tracking strategy, lazy updates as fallback

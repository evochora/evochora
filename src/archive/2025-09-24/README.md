# Archived Code - September 24, 2025

## Reason for Archiving
This archive contains the old `datapipeline` and `server` packages that were archived to enable a clean restart of the datapipeline implementation. The runtime package was successfully decoupled from these packages by removing export functionality.

## What was Archived
- **datapipeline package** (main and test code) - V2 implementation that was being developed
- **server package** (main and test code) - Original server implementation

## Key Changes Made Before Archiving
1. **Runtime Decoupling**: Removed export methods `createRawOrganismState()` and `getOccupiedCells()` from runtime
2. **Removed Dependencies**: Runtime no longer depends on `server.contracts.raw.*`
3. **Cache Cleanup**: Removed all change-tracking and caching code from `Organism` class

## Runtime Public API (Still Available)
The runtime package provides a complete public API for accessing organism and environment data:

### Organism
- `getId()`, `getParentId()`, `getBirthTick()`, `getProgramId()`
- `getIp()`, `getDv()`, `getEr()`, `getActiveDpIndex()`
- `getDrs()`, `getPrs()`, `getFprs()`, `getDps()`, `getLrs()`
- `getDataStack()`, `getLocationStack()`, `getCallStack()`
- `isDead()`, `isInstructionFailed()`, `getFailureReason()`

### Environment  
- `getMolecule(int[] coord)` - Access individual cells
- `getOwnerId(int[] coord)` - Cell ownership information
- `getShape()`, `getProperties()` - World structure

## Next Steps
A new datapipeline implementation should be created that:
1. Uses the runtime's public API for data access
2. Implements its own export/serialization logic
3. Maintains clean separation between simulation core and data pipeline

## Archive Structure
```
src/archive/2025-09-24/
├── main/java/org/evochora/
│   ├── datapipeline/  # Complete datapipeline V2 implementation
│   └── server/        # Original server implementation
└── test/java/org/evochora/
    ├── datapipeline/  # All datapipeline tests
    └── server/        # All server tests
```

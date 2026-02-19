package org.evochora.datapipeline.api.resources.storage;

/**
 * Controls which fields are skipped during wire-level protobuf parsing of {@code TickDataChunk}.
 * <p>
 * Each filter value specifies a semantic category of data to skip. The actual protobuf field
 * numbers differ between {@code TickData} (snapshot) and {@code TickDelta} (delta), but the
 * enum abstracts this: {@code SKIP_ORGANISMS} skips organism fields in both message types.
 * <p>
 * <strong>Memory impact:</strong> Skipping a field at the wire level means the bytes are
 * discarded from the {@link com.google.protobuf.CodedInputStream} without allocating Java
 * objects. For a 4000x3000 environment with ~580 organisms, skipping organisms saves ~730 MB
 * per chunk and skipping cells saves ~550 MB per snapshot.
 *
 * @see IBatchStorageRead#forEachChunk(StoragePath, ChunkFieldFilter, CheckedConsumer)
 */
public enum ChunkFieldFilter {

    /**
     * Parse all fields (default behavior). No fields are skipped.
     */
    ALL,

    /**
     * Skip organism data in both snapshots and deltas.
     * <p>
     * Use this for indexers that only need environment cell data (e.g., EnvironmentIndexer).
     * Skips {@code TickData.organisms} (field 4) and {@code TickDelta.organisms} (field 5).
     */
    SKIP_ORGANISMS,

    /**
     * Skip cell/environment data in both snapshots and deltas.
     * <p>
     * Use this for indexers that only need organism data (e.g., OrganismIndexer).
     * Skips {@code TickData.cell_columns} (field 5) and {@code TickDelta.changed_cells} (field 4).
     */
    SKIP_CELLS
}

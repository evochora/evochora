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
 * <p>
 * <strong>The other axis.</strong> A filter speaks for a whole chunk and only about categories:
 * organisms or cells, never both. Which of the recorded ticks are worth materializing at all is a
 * separate question, answered per tick by {@link ITickRelevance} within whatever the filter lets
 * through. A reader that needs organisms at some ticks and cells at others therefore passes
 * {@link #ALL} together with a relevance - there its saving comes from the ticks, not from the
 * category.
 *
 * @see IBatchStorageRead#forEachChunk(StoragePath, ChunkFieldFilter, ITickRelevance, CheckedConsumer)
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
     * Skips the organism list of the snapshot and of every delta.
     */
    SKIP_ORGANISMS,

    /**
     * Skip cell/environment data in both snapshots and deltas.
     * <p>
     * Use this for indexers that only need organism data (e.g., OrganismIndexer).
     * Skips the cell data of the snapshot and of every delta.
     */
    SKIP_CELLS,

    /**
     * Skip all delta messages entirely. Only chunk metadata and the snapshot are parsed.
     * <p>
     * Use this for resume operations that only need the final snapshot from a batch.
     * Skips {@code TickDataChunk.deltas} at the wire level — delta bytes are discarded without
     * deserialization.
     */
    SNAPSHOT_ONLY
}

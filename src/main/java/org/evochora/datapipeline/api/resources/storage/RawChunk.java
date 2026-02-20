package org.evochora.datapipeline.api.resources.storage;

/**
 * Raw protobuf bytes of a single {@link org.evochora.datapipeline.api.contracts.TickDataChunk}
 * message with minimal metadata extracted via partial parse.
 * <p>
 * Used by the raw-byte streaming path to avoid the heap cost of full protobuf parsing.
 * For a 4000x3000 environment, one raw chunk is ~25 MB (vs ~700 MB for parsed Java objects).
 * <p>
 * The {@code data} field contains the complete uncompressed protobuf bytes, including organism
 * data. Consumers that need organism-free data should filter at read time (e.g., wire-level
 * filtering in {@code RowPerChunkStrategy.parseChunkForEnvironment}).
 *
 * @param firstTick first tick number in this chunk
 * @param lastTick  last tick number in this chunk
 * @param tickCount number of ticks in this chunk (cannot be derived from firstTick/lastTick
 *                  because the sampling interval may be greater than 1)
 * @param data      uncompressed protobuf bytes of the TickDataChunk message
 */
public record RawChunk(long firstTick, long lastTick, int tickCount, byte[] data) {}

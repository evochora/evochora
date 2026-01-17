package org.evochora.datapipeline.api.delta;

/**
 * Thrown when a {@link org.evochora.datapipeline.api.contracts.TickDataChunk} is corrupt
 * or cannot be decompressed.
 * <p>
 * This is a checked exception following the pattern of
 * {@link org.evochora.datapipeline.utils.compression.CompressionException}.
 * <p>
 * <strong>Error Handling Pattern:</strong> Callers (services, indexers) should catch this
 * exception and handle according to AGENTS.md guidelines:
 * <ul>
 *   <li>{@code log.warn("msg", args)} - NO exception parameter (stack trace at DEBUG level)</li>
 *   <li>{@code recordError(code, msg, details)} - for health monitoring</li>
 *   <li>Continue processing (skip corrupt chunk, never abort simulation)</li>
 * </ul>
 * <p>
 * <strong>Example:</strong>
 * <pre>{@code
 * try {
 *     List<TickData> ticks = DeltaCodec.decompressChunk(chunk, totalCells);
 *     processTicks(ticks);
 * } catch (ChunkCorruptedException e) {
 *     log.warn("Skipping corrupt chunk (firstTick={}): {}", chunk.getFirstTick(), e.getMessage());
 *     recordError("CHUNK_CORRUPT", "Corrupt chunk skipped",
 *                 "FirstTick: " + chunk.getFirstTick() + ", Reason: " + e.getMessage());
 *     // Continue with next chunk - simulation keeps running!
 * }
 * }</pre>
 *
 * @see org.evochora.datapipeline.utils.delta.DeltaCodec
 */
public class ChunkCorruptedException extends Exception {
    
    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message the detail message explaining the corruption
     */
    public ChunkCorruptedException(String message) {
        super(message);
    }
    
    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message explaining the corruption
     * @param cause the underlying cause of the corruption
     */
    public ChunkCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}

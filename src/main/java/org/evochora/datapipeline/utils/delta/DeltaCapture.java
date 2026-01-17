package org.evochora.datapipeline.utils.delta;

/**
 * Temporary holder for delta data during chunk construction.
 * <p>
 * The SimulationEngine creates a {@code DeltaCapture} for each sampled tick
 * (except snapshots) and accumulates them until a chunk is complete.
 * <p>
 * <strong>Lifecycle:</strong>
 * <ol>
 *   <li>SimulationEngine samples a tick</li>
 *   <li>Determines delta type (INCREMENTAL or ACCUMULATED)</li>
 *   <li>Extracts changed cells and organism states</li>
 *   <li>Creates DeltaCapture via {@link DeltaCodec#createDelta}</li>
 *   <li>Accumulates in list until chunk boundary</li>
 *   <li>Calls {@link DeltaCodec#createChunk} with snapshot + deltas</li>
 * </ol>
 * <p>
 * <strong>Thread Safety:</strong> Immutable record, safe for concurrent access.
 *
 * @param tickNumber the simulation tick number
 * @param captureTimeMs wall-clock capture time in milliseconds
 * @param delta the constructed TickDelta protobuf message
 * @see DeltaCodec#createDelta
 * @see DeltaCodec#createChunk
 */
public record DeltaCapture(
        long tickNumber,
        long captureTimeMs,
        org.evochora.datapipeline.api.contracts.TickDelta delta
) {
    /**
     * Creates a new DeltaCapture.
     *
     * @param tickNumber the simulation tick number
     * @param captureTimeMs wall-clock capture time
     * @param delta the TickDelta message (must not be null)
     * @throws NullPointerException if delta is null
     */
    public DeltaCapture {
        if (delta == null) {
            throw new NullPointerException("delta must not be null");
        }
    }
}

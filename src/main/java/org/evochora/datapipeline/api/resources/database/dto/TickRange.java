package org.evochora.datapipeline.api.resources.database.dto;

/**
 * Represents the range of available ticks in a simulation run.
 * <p>
 * Contains the minimum and maximum tick numbers that are available in the database
 * for a specific simulation run. Used by the visualizer API to determine tick boundaries.
 *
 * @param minTick The minimum tick number available (inclusive)
 * @param maxTick The maximum tick number available (inclusive)
 */
public record TickRange(long minTick, long maxTick) {
    /**
     * Validates that minTick is not greater than maxTick.
     *
     * @throws IllegalArgumentException if minTick > maxTick
     */
    public TickRange {
        if (minTick > maxTick) {
            throw new IllegalArgumentException(
                String.format("minTick (%d) cannot be greater than maxTick (%d)", minTick, maxTick)
            );
        }
    }
}


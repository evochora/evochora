package org.evochora.datapipeline.api.resources.database.dto;

/**
 * One contiguous stretch of recorded ticks, sampled at a single step.
 * <p>
 * A run does not record every tick: it records {@code first}, {@code first + step},
 * {@code first + 2 × step} and so on up to {@code last}. The step is the sampling interval the
 * chunks of the stretch were recorded at, which each of them states. Nothing between those ticks
 * exists in the run, and nothing outside the stretch is promised by it - a run forked from another
 * starts at the fork's first tick rather than at 0, and a run may hold several stretches of
 * different step. A viewer navigates from range to range and must not assume any other tick is
 * there.
 *
 * @param first The first recorded tick of the stretch (inclusive)
 * @param last The last recorded tick of the stretch (inclusive)
 * @param step The distance between two consecutive recorded ticks, at least 1
 */
public record SampledTickRange(long first, long last, long step) {
    /**
     * Validates that the stretch runs forward, that its step can be stepped along, and that the
     * last tick lies on the grid the first tick and the step define.
     *
     * @throws IllegalArgumentException if first is greater than last, step is less than 1, or
     *                                  last is not first plus a whole number of steps
     */
    public SampledTickRange {
        if (first > last) {
            throw new IllegalArgumentException(
                String.format("first (%d) cannot be greater than last (%d)", first, last)
            );
        }
        if (step < 1) {
            throw new IllegalArgumentException(
                String.format("step must be at least 1, got %d for ticks %d..%d", step, first, last)
            );
        }
        if ((last - first) % step != 0) {
            throw new IllegalArgumentException(
                String.format("last (%d) is not first (%d) plus a whole number of steps of %d", last, first, step)
            );
        }
    }
}

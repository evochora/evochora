package org.evochora.node.processes.http.api.visualizer.dto;

import java.util.List;

import org.evochora.datapipeline.api.resources.database.dto.SampledTickRange;

/**
 * Response DTO for the environment tick range endpoint.
 * <p>
 * {@code minTick} and {@code maxTick} are the outer bounds of what the run has recorded, the
 * first tick of the first range and the last tick of the last. Between them, only the ticks the
 * ranges name exist: each range is one contiguous stretch recorded at a single step, and a run
 * may start away from tick 0 or hold several stretches of different step. A viewer navigates
 * along the ranges and must not assume that any tick outside them, or between the steps within
 * one, can be requested.
 *
 * @param minTick The lowest recorded tick of the run (inclusive)
 * @param maxTick The highest recorded tick of the run (inclusive)
 * @param ranges The recorded stretches, ordered by their first tick, at least one
 */
public record TickRangesResponseDto(
    long minTick,
    long maxTick,
    List<SampledTickRange> ranges
) {}

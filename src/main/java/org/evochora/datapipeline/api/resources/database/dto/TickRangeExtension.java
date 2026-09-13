package org.evochora.datapipeline.api.resources.database.dto;

import java.util.List;

/**
 * Tick ranges together with what the read that produced them took in.
 * <p>
 * Ranges are built by walking the chunk index from left to right, and a read either starts from
 * nothing or continues a list of ranges that an earlier read left behind. Both are extensions of
 * a list of ranges by a stretch of chunks, which is why both are described by this record.
 * <p>
 * The three numbers beside the ranges are what a caller needs to decide whether the extension may
 * be kept: comparing the chunks and ticks taken in against the growth the chunk index reports
 * shows whether the index only grew at its end, and {@code lastFirstTick} says where the next
 * read continues.
 *
 * @param ranges The ranges after the read, ordered by first tick
 * @param addedChunks Number of chunks this read took in
 * @param addedSamples The ticks those chunks hold together
 * @param lastFirstTick First tick of the last chunk read, or the bound the read started from when
 *                     it found no chunk at all
 */
public record TickRangeExtension(
    List<SampledTickRange> ranges,
    long addedChunks,
    long addedSamples,
    long lastFirstTick
) {}

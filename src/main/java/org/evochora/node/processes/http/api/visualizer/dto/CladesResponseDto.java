package org.evochora.node.processes.http.api.visualizer.dto;

import java.util.List;

/**
 * The descent of a run's genomes and how the population divided between them at sampled ticks.
 * <p>
 * Genomes are named once, in {@code genomes}, and referred to by their position everywhere else.
 * A run holds tens of thousands of them and a sample holds one entry per genome alive at its
 * tick; spelling out a 64-bit hash in each of those entries would make the answer several times
 * larger than the numbers it carries.
 * <p>
 * Hashes are strings because 64 bits do not survive a JavaScript number.
 *
 * @param genomes Every genome of the run, each hash as a string
 * @param parents Parallel to {@code genomes}: the position of that genome's parent, or -1 where
 *                the genome begins a line
 * @param samples One entry per requested tick that holds data, ordered by tick
 */
public record CladesResponseDto(
        List<String> genomes,
        List<Integer> parents,
        List<CladeSampleDto> samples
) {
    /**
     * What one sampled tick holds: the genomes alive in it and how many organisms carried each.
     *
     * @param tick The tick sampled
     * @param carriers Pairs of genome position and carrier count, as flat two-element arrays
     */
    public record CladeSampleDto(long tick, List<int[]> carriers) {
    }
}

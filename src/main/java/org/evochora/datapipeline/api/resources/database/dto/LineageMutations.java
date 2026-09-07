package org.evochora.datapipeline.api.resources.database.dto;

import org.evochora.datapipeline.api.contracts.StoredMutationEvents;

/**
 * One organism of an ancestry chain together with the mutations it received at its own birth.
 * <p>
 * The events are kept in their stored form, with the cells as offsets from
 * {@link #initialPosition()}. That offset is the same seen from every descendant's origin, which
 * is what lets the events of a whole chain be laid over one displayed body: the displayed
 * organism's own initial position plus the offset gives the cell on that body.
 * <p>
 * The organism's identity travels with the events because an event belongs to the birth it was
 * recorded at, not to the organism it is displayed on. Generation, genome hash and birth tick say
 * which birth that was. The parent's genome hash travels with it because a display that colours a
 * mutation by the genome it arose in needs that genome's place in the ancestry, and a genome no
 * organism carries any more is found nowhere else in the answer.
 *
 * @param organismId The organism that received these mutations at its birth
 * @param generation Its generation, zero for an organism placed at the start of the run
 * @param genomeHash Its genome hash at birth
 * @param parentGenomeHash The genome hash its parent carried at that birth, {@code null} for an
 *                         organism without a parent and zero for a parent without a genome
 * @param birthTick The tick it came into existence at
 * @param initialPosition The coordinates it started at, one component per world dimension
 * @param events Its birth mutations, the default instance for an organism that carries none
 */
public record LineageMutations(
        int organismId,
        int generation,
        long genomeHash,
        Long parentGenomeHash,
        long birthTick,
        int[] initialPosition,
        StoredMutationEvents events) {}

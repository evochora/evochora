package org.evochora.datapipeline.api.resources.database.dto;

/**
 * How many organisms carried one genome at one tick.
 * <p>
 * The flat form the database answers in: one entry per tick and genome, rather than a tick
 * holding a map of genomes. Both numbers are genome-sized and would otherwise stand as bare
 * {@code long}s beside each other, where only their position would tell them apart.
 *
 * @param tickNumber The tick the count was taken at
 * @param genomeHash The genome counted; never 0, which stands for an organism without a genome
 * @param carriers How many organisms of that tick carried it, at least 1
 */
public record GenomeCarriers(long tickNumber, long genomeHash, int carriers) {
}

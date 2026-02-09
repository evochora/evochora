package org.evochora.datapipeline.api.resources.database.dto;

/**
 * A single entry in an organism's ancestry chain.
 *
 * @param organismId The ancestor's organism ID.
 * @param genomeHash The ancestor's genome hash at birth.
 */
public record LineageEntry(int organismId, long genomeHash) {}

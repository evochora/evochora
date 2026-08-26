package org.evochora.node.processes.http.api.visualizer.dto;

import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for the organisms list endpoint.
 * <p>
 * Contains the list of organisms at a specific tick (alive and recently dead),
 * the cumulative count of all organisms ever created up to that tick,
 * and the ancestor closure of the genomes occurring in that list, which is what
 * lineage-based colour computation needs.
 * <p>
 * Genome hashes are serialized as strings to preserve 64-bit precision in JSON
 * (JavaScript numbers lose precision beyond 2^53).
 *
 * @param organisms List of organism summaries at the specified tick
 * @param totalOrganismCount Total organisms created up to this tick
 * @param genomeAncestors Mapping of genomeHash → parentGenomeHash as strings, null for root genomes,
 *                        covering every genome in the list and all of their ancestors
 */
public record OrganismsResponseDto(
    List<OrganismTickSummary> organisms,
    int totalOrganismCount,
    Map<String, String> genomeAncestors
) {}

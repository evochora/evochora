package org.evochora.node.processes.http.api.visualizer.dto;

import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for the organisms list endpoint.
 * <p>
 * Contains the list of organisms at a specific tick (alive and recently dead),
 * the cumulative count of all organisms ever created up to that tick,
 * and the genome lineage tree for lineage-based color computation.
 * <p>
 * Genome hashes are serialized as strings to preserve 64-bit precision in JSON
 * (JavaScript numbers lose precision beyond 2^53).
 *
 * @param organisms List of organism summaries at the specified tick
 * @param totalOrganismCount Total organisms created up to this tick (sequential IDs from 1)
 * @param genomeLineageTree Mapping of genomeHash → parentGenomeHash as strings (null for root genomes)
 */
public record OrganismsResponseDto(
    List<OrganismTickSummary> organisms,
    int totalOrganismCount,
    Map<String, String> genomeLineageTree
) {}

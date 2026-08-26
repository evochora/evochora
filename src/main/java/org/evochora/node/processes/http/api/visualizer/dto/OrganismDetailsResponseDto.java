package org.evochora.node.processes.http.api.visualizer.dto;

import org.evochora.datapipeline.api.resources.database.dto.OrganismRuntimeView;
import org.evochora.datapipeline.api.resources.database.dto.OrganismStaticInfo;

import java.util.Map;

/**
 * Response DTO for the organism details endpoint.
 * <p>
 * Carries the same fields as the reader's detail view, plus the ancestor closure of the genomes
 * occurring in the organism's ancestry chain. The chain is coloured by genome like the organisms
 * of a tick, and an ancestor can carry a genome that is not an ancestor genome of the displayed
 * organism, so the closure of a tick does not cover it. Each response therefore carries what it
 * needs to be rendered on its own.
 * <p>
 * Genome hashes are serialized as strings to preserve 64-bit precision in JSON
 * (JavaScript numbers lose precision beyond 2^53).
 *
 * @param organismId Identifier of the organism
 * @param tick Tick the state belongs to
 * @param staticInfo Immutable organism data including its ancestry chain
 * @param state Runtime state at the given tick
 * @param genomeAncestors Mapping of genomeHash → parentGenomeHash as strings, null for root genomes
 */
public record OrganismDetailsResponseDto(
    int organismId,
    long tick,
    OrganismStaticInfo staticInfo,
    OrganismRuntimeView state,
    Map<String, String> genomeAncestors
) {}

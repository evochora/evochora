package org.evochora.node.processes.http.api.visualizer.dto;

import org.evochora.datapipeline.api.resources.database.dto.LineageEntry;
import org.evochora.datapipeline.api.resources.database.dto.OrganismRuntimeView;
import org.evochora.datapipeline.api.resources.database.dto.OrganismStaticInfo;

import java.util.List;

/**
 * Response DTO for the organism details endpoint.
 * <p>
 * Carries the same fields as the reader's detail view.
 *
 * @param organismId Identifier of the organism
 * @param tick Tick the state belongs to
 * @param staticInfo What the organisms table holds about the organism
 * @param lineage Ancestry chain, direct parent first, oldest ancestor last
 * @param labelNamespaceMask The label namespace the organism's body stands in
 * @param state Runtime state at the given tick
 */
public record OrganismDetailsResponseDto(
    int organismId,
    long tick,
    OrganismStaticInfo staticInfo,
    List<LineageEntry> lineage,
    int labelNamespaceMask,
    OrganismRuntimeView state
) {}

package org.evochora.node.processes.http.api.visualizer.dto;

import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;

import java.util.List;

/**
 * Response DTO for the organisms list endpoint.
 * <p>
 * Contains the list of organisms that are alive at a specific tick.
 * This is a minimal wrapper that matches the client's expected response structure.
 *
 * @param organisms List of organism summaries at the specified tick
 */
public record OrganismsResponseDto(
    List<OrganismTickSummary> organisms
) {}


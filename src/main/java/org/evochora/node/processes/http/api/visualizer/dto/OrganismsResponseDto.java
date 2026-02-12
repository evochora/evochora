package org.evochora.node.processes.http.api.visualizer.dto;

import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;

import java.util.List;

/**
 * Response DTO for the organisms list endpoint.
 * <p>
 * Contains the list of organisms at a specific tick (alive and recently dead)
 * plus the cumulative count of all organisms ever created up to that tick.
 *
 * @param organisms List of organism summaries at the specified tick
 * @param totalOrganismCount Total organisms created up to this tick (sequential IDs from 1)
 */
public record OrganismsResponseDto(
    List<OrganismTickSummary> organisms,
    int totalOrganismCount
) {}


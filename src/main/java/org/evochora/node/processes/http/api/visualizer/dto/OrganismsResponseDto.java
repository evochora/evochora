package org.evochora.node.processes.http.api.visualizer.dto;

import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response DTO for the organisms list endpoint.
 * <p>
 * Contains the list of organisms at a specific tick (alive and recently dead),
 * the cumulative count of all organisms ever created up to that tick, and, when the request
 * names a root of descent, how the organisms of the tick descend from it.
 *
 * @param organisms List of organism summaries at the specified tick
 * @param totalOrganismCount Total organisms created up to this tick
 * @param descent Descent of the tick's organisms from the requested root; {@code null}, and left
 *                out of the JSON, when the request names no root
 */
public record OrganismsResponseDto(
    List<OrganismTickSummary> organisms,
    int totalOrganismCount,
    @JsonInclude(JsonInclude.Include.NON_NULL) DescentDto descent
) {}

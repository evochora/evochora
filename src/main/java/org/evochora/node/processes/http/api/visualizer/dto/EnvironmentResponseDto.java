package org.evochora.node.processes.http.api.visualizer.dto;

import org.evochora.datapipeline.api.resources.database.dto.CellWithCoordinates;

import java.util.List;

/**
 * Response DTO for the environment data endpoint.
 * <p>
 * Contains the list of cells for the requested region at a specific tick.
 * This is a minimal wrapper that matches the client's expected response structure.
 *
 * @param cells List of cells with coordinates for the requested region
 */
public record EnvironmentResponseDto(
    List<CellWithCoordinates> cells
) {}


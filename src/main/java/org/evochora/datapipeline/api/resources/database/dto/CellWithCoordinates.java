package org.evochora.datapipeline.api.resources.database.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/**
 * Cell data with coordinates for client rendering.
 * <p>
 * For CODE molecules, {@code opcodeName} contains the instruction name (e.g., "SETI", "ADD").
 * For other molecule types, {@code opcodeName} is {@code null}.
 *
 * @param coordinates   The coordinates of the cell in the environment.
 * @param moleculeType  The type name of the molecule (e.g., "CODE", "DATA", "ENERGY", "STRUCTURE").
 * @param moleculeValue The value of the molecule.
 * @param ownerId       The ID of the organism that owns this cell (0 if unowned).
 * @param opcodeName    The instruction name for CODE molecules, or {@code null} for other types.
 * @param marker        The molecule marker (0-15), used for ownership transfer during FORK.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public record CellWithCoordinates(
    int[] coordinates,
    String moleculeType,
    int moleculeValue,
    int ownerId,
    String opcodeName,
    int marker
) {}

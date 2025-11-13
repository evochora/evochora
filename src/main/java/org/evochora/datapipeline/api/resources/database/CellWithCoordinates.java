package org.evochora.datapipeline.api.resources.database;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/**
 * Cell data with coordinates for client rendering.
 * <p>
 * For CODE molecules, {@code opcodeName} contains the instruction name (e.g., "SETI", "ADD").
 * For other molecule types, {@code opcodeName} is {@code null}.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public record CellWithCoordinates(
    int[] coordinates,
    String moleculeType,
    int moleculeValue,
    int ownerId,
    String opcodeName
) {}

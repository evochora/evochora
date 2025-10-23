package org.evochora.datapipeline.api.resources.database;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/**
 * Cell data with coordinates for client rendering.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public record CellWithCoordinates(
    int[] coordinates,
    int moleculeType,
    int moleculeValue,
    int ownerId
) {}

package org.evochora.datapipeline.api.services;

import java.util.List;

/**
 * An immutable data object representing the complete status of a service at a point in time.
 *
 * @param state           The overall lifecycle state of the service.
 * @param channelBindings A list of status objects for each of the service's channel connections.
 */
public record ServiceStatus(
    State state,
    List<ChannelBindingStatus> channelBindings
) {
}

package org.evochora.datapipeline.api.services;

/**
 * An immutable data object describing the status of a single connection between a service and a channel.
 *
 * @param channelName       The name of the channel.
 * @param direction         The direction of the data flow (INPUT or OUTPUT).
 * @param state             The current state of the binding (e.g., ACTIVE, WAITING).
 * @param messagesPerSecond The recent throughput of the binding, in messages per second.
 */
public record ChannelBindingStatus(
    String channelName,
    Direction direction,
    BindingState state,
    double messagesPerSecond
) {
}

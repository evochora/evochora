package org.evochora.datapipeline.api.services;

/**
 * Immutable record containing channel throughput metrics and metadata.
 * Used to store calculated throughput values with timestamps and error tracking.
 * 
 * @param messagesPerSecond The calculated throughput in messages per second
 * @param timestamp The timestamp when this metric was calculated (System.currentTimeMillis())
 * @param errorCount The number of errors encountered during metric collection
 */
public record ChannelMetrics(
    double messagesPerSecond,
    long timestamp,
    int errorCount
) {
    
    /**
     * Creates a ChannelMetrics record with the current timestamp.
     * 
     * @param messagesPerSecond The throughput value
     * @param errorCount The error count
     * @return A new ChannelMetrics instance with current timestamp
     */
    public static ChannelMetrics withCurrentTimestamp(double messagesPerSecond, int errorCount) {
        return new ChannelMetrics(messagesPerSecond, System.currentTimeMillis(), errorCount);
    }
    
    /**
     * Creates a ChannelMetrics record with zero values and current timestamp.
     * 
     * @return A new ChannelMetrics instance with zero throughput and errors
     */
    public static ChannelMetrics zero() {
        return withCurrentTimestamp(0.0, 0);
    }
}

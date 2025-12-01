package org.evochora.datapipeline.api.analytics;

import com.typesafe.config.Config;

/**
 * Abstract base class for Analytics Plugins.
 * <p>
 * Handles common configuration and provides default implementations.
 * Subclasses only need to implement:
 * <ul>
 *   <li>{@link #getSchema()} - Define the Parquet schema</li>
 *   <li>{@link #extractRows(org.evochora.datapipeline.api.contracts.TickData)} - Extract data from ticks</li>
 *   <li>{@link #getManifestEntry()} - Describe the metric for frontend</li>
 * </ul>
 * <p>
 * The indexer handles all I/O: DuckDB, Parquet generation, LOD aggregation, and storage.
 * <p>
 * <strong>Example:</strong>
 * <pre>{@code
 * public class MyMetricsPlugin extends AbstractAnalyticsPlugin {
 *     @Override
 *     public ParquetSchema getSchema() {
 *         return ParquetSchema.builder()
 *             .column("tick", ColumnType.BIGINT)
 *             .column("count", ColumnType.INTEGER)
 *             .build();
 *     }
 *
 *     @Override
 *     public List<Object[]> extractRows(TickData tick) {
 *         int count = tick.getOrganismsCount();
 *         return List.of(new Object[] { tick.getTickNumber(), count });
 *     }
 *
 *     @Override
 *     public ManifestEntry getManifestEntry() {
 *         ManifestEntry entry = new ManifestEntry();
 *         entry.id = metricId;
 *         entry.name = "My Metric";
 *         // ... configure visualization hints ...
 *         return entry;
 *     }
 * }
 * }</pre>
 */
public abstract class AbstractAnalyticsPlugin implements IAnalyticsPlugin {
    
    /** Plugin configuration from HOCON. */
    protected Config config;
    
    /** Context providing access to metadata and run information. */
    protected IAnalyticsContext context;
    
    /** Unique metric identifier (from config). */
    protected String metricId;
    
    /** Sampling interval: process every Nth tick. Default is 1 (every tick). */
    protected int samplingInterval = 1;
    
    /**
     * {@inheritDoc}
     * <p>
     * Reads standard configuration:
     * <ul>
     *   <li>{@code metricId} - Required unique identifier</li>
     *   <li>{@code samplingInterval} - Optional, default 1</li>
     * </ul>
     * Subclasses can override to read additional config, but must call {@code super.configure(config)}.
     */
    @Override
    public void configure(Config config) {
        this.config = config;
        this.metricId = config.getString("metricId");
        if (config.hasPath("samplingInterval")) {
            this.samplingInterval = config.getInt("samplingInterval");
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Stores the context for subclass access. Subclasses can override for
     * additional initialization, but must call {@code super.initialize(context)}.
     */
    @Override
    public void initialize(IAnalyticsContext context) {
        this.context = context;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Default implementation does nothing. Override if cleanup is needed.
     */
    @Override
    public void onFinish() {
        // Default: no cleanup needed
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public String getMetricId() {
        return metricId;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public int getSamplingInterval() {
        return samplingInterval;
    }
    
    // Abstract methods that subclasses MUST implement:
    // - getSchema()
    // - extractRows(TickData)
    // - getManifestEntry()
}

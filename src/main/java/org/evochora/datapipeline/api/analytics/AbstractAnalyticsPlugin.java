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
    
    /** LOD factor: each higher level samples lodFactor^level times. Default is 10. */
    protected int lodFactor = 10;
    
    /** Number of LOD levels to generate. Default is 3 (lod0, lod1, lod2). */
    protected int lodLevels = 3;
    
    /**
     * {@inheritDoc}
     * <p>
     * Reads standard configuration:
     * <ul>
     *   <li>{@code metricId} - Required unique identifier</li>
     *   <li>{@code samplingInterval} - Optional, default 1</li>
     *   <li>{@code lodFactor} - Optional, default 10</li>
     *   <li>{@code lodLevels} - Optional, default 1</li>
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
        if (config.hasPath("lodFactor")) {
            this.lodFactor = config.getInt("lodFactor");
        }
        if (config.hasPath("lodLevels")) {
            this.lodLevels = config.getInt("lodLevels");
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
    
    /**
     * {@inheritDoc}
     */
    @Override
    public int getLodFactor() {
        return lodFactor;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public int getLodLevels() {
        return lodLevels;
    }
    
    /**
     * Calculates the effective sampling interval for a specific LOD level.
     * <p>
     * Formula: {@code baseSamplingInterval * lodFactor^level}
     * <p>
     * Example with samplingInterval=1, lodFactor=10:
     * <ul>
     *   <li>lod0: 1 * 10^0 = 1</li>
     *   <li>lod1: 1 * 10^1 = 10</li>
     *   <li>lod2: 1 * 10^2 = 100</li>
     * </ul>
     *
     * @param level LOD level (0, 1, 2, ...)
     * @return Effective sampling interval for this level
     */
    public int getEffectiveSamplingInterval(int level) {
        return samplingInterval * (int) Math.pow(lodFactor, level);
    }
    
    /**
     * Helper method to generate LOD level name.
     *
     * @param level LOD level number (0, 1, 2, ...)
     * @return LOD level name (e.g., "lod0", "lod1", "lod2")
     */
    public static String lodLevelName(int level) {
        return "lod" + level;
    }
    
    // Abstract methods that subclasses MUST implement:
    // - getSchema()
    // - extractRows(TickData)
    // - getManifestEntry()
}

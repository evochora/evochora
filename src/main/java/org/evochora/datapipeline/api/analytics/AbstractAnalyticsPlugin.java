package org.evochora.datapipeline.api.analytics;

import org.evochora.datapipeline.utils.MetadataConfigHelper;

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
    
    /** Sampling interval: process every Nth recorded tick. Default is 1 (every recorded tick). */
    protected int samplingInterval = 1;
    
    /** LOD factor: each higher level samples lodFactor^level times. Default is 10. */
    protected int lodFactor = 10;
    
    /** Number of LOD levels to generate. Default is 1 (lod0). */
    protected int lodLevels = 1;

    /** Maximum data points the frontend should load at once. Null means frontend default. */
    protected Integer maxDataPoints = null;

    /**
     * Effective sampling interval per LOD level, in absolute ticks.
     * <p>
     * Computed in {@link #initialize(IAnalyticsContext)} from the run's recording interval and
     * this plugin's configuration. {@code null} until a context carrying simulation metadata has
     * been supplied.
     */
    private int[] effectiveSamplingIntervals;
    
    /**
     * {@inheritDoc}
     * <p>
     * Reads standard configuration:
     * <ul>
     *   <li>{@code metricId} - Required unique identifier</li>
     *   <li>{@code samplingInterval} - Optional, default 1 (every recorded tick)</li>
     *   <li>{@code lodFactor} - Optional, default 10</li>
     *   <li>{@code lodLevels} - Optional, default 1</li>
     * </ul>
     * A plugin for which only one value of an option is right states it through
     * {@link #fixedSamplingInterval()} or {@link #fixedLodLevels()}; configuring such an option is
     * then refused rather than obeyed or ignored.
     * <ul>
     *   <li>{@code maxDataPoints} - Optional, default null (frontend decides)</li>
     * </ul>
     * Subclasses can override to read additional config, but must call {@code super.configure(config)}.
     */
    @Override
    public void configure(Config config) {
        this.config = config;
        this.metricId = config.getString("metricId");
        this.samplingInterval = settle(config, "samplingInterval", fixedSamplingInterval(), samplingInterval);
        this.lodLevels = settle(config, "lodLevels", fixedLodLevels(), lodLevels);
        if (config.hasPath("lodFactor")) {
            this.lodFactor = requireAtLeastOne(config.getInt("lodFactor"), "lodFactor");
        }
        if (config.hasPath("maxDataPoints")) {
            this.maxDataPoints = config.getInt("maxDataPoints");
        }
    }

    /**
     * A value a plugin sets itself, with the reason it cannot be configured.
     *
     * @param value  the value that holds for this metric
     * @param reason what about the metric makes any other value wrong, phrased to follow
     *               "cannot be configured, because ..."
     */
    public record Fixed(int value, String reason) { }

    /**
     * The sampling interval this metric requires, or {@code null} if it may be configured.
     * <p>
     * A metric reporting what happened since the previous recording cannot skip one: what fell
     * into the gap is not reported late, it is not reported at all. Such a metric states its
     * interval here instead of trusting a configuration file to carry it.
     *
     * @return the interval this metric requires, or {@code null}
     */
    protected Fixed fixedSamplingInterval() {
        return null;
    }

    /**
     * The number of levels of detail this metric requires, or {@code null} if it may be configured.
     * <p>
     * A level of detail selects rows, which is a coarser picture of a quantity but a broken one of
     * a structure. A metric carrying structure states its single level here.
     *
     * @return the number of levels this metric requires, or {@code null}
     */
    protected Fixed fixedLodLevels() {
        return null;
    }

    /**
     * Determines an option's value from what the plugin fixes and what the configuration says.
     * <p>
     * A configured value is refused wherever the plugin fixes one - including a value that agrees.
     * Two places stating the same number are two places that can come to differ, and the one in
     * the configuration file would then win silently over the one the code needs.
     *
     * @param config   the plugin's configuration
     * @param option   the option's name
     * @param fixed    what the plugin fixes, or {@code null}
     * @param fallback the value to keep when neither fixes nor configures it
     * @return the value to use
     * @throws IllegalArgumentException if the option is configured although the plugin fixes it,
     *         or if a configured value is below one
     */
    private int settle(Config config, String option, Fixed fixed, int fallback) {
        if (fixed == null) {
            return config.hasPath(option) ? requireAtLeastOne(config.getInt(option), option) : fallback;
        }
        if (config.hasPath(option)) {
            throw new IllegalArgumentException("Metric '" + metricId + "': " + option
                + " is " + fixed.value() + " for this metric and cannot be configured, because "
                + fixed.reason() + ". Remove it from the configuration.");
        }
        return fixed.value();
    }

    /**
     * Rejects a configured interval below one.
     * <p>
     * All three interval options are used as divisors or as loop bounds, where a zero turns into
     * an arithmetic failure per batch or into a metric that produces nothing at all. Rejecting the
     * value where it is read names the metric and the option; failing later names neither.
     *
     * @param value  The configured value
     * @param option The option name, for the error message
     * @return The value, if it is at least one
     * @throws IllegalArgumentException if the value is below one
     */
    private int requireAtLeastOne(int value, String option) {
        if (value < 1) {
            throw new IllegalArgumentException(
                "Metric '" + metricId + "': " + option + " must be at least 1, but is " + value + ".");
        }
        return value;
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
        // A context without metadata is a caller that does not use the tick grid at all - unit
        // tests exercising extractRows. A context WITH metadata must carry the recording interval:
        // deferring that failure would surface it inside the indexer's per-plugin bulkhead, where
        // it would be logged and the metric dropped for the whole run.
        if (context != null) {
            this.effectiveSamplingIntervals = computeEffectiveSamplingIntervals(
                readRecordingInterval(context));
        }
    }

    /**
     * Reads the run's recording interval - the number of simulation ticks between two ticks
     * written to storage - from the simulation metadata.
     *
     * @param context The analytics context carrying the metadata
     * @return The recording interval in ticks
     * @throws IllegalStateException if the context carries no metadata, or the metadata does not
     *         state a recording interval, since every tick grid derived from it would then be a
     *         guess
     */
    private int readRecordingInterval(IAnalyticsContext context) {
        if (context.getMetadata() == null || context.getMetadata().getResolvedConfigJson().isEmpty()) {
            throw new IllegalStateException(
                "Metric '" + metricId + "': the analytics context carries no run configuration. "
                + "The recording interval is required to place metric rows on the run's tick grid.");
        }
        Config resolvedConfig = MetadataConfigHelper.getResolvedConfig(context.getMetadata());
        if (!resolvedConfig.hasPath("samplingInterval")) {
            throw new IllegalStateException(
                "Metric '" + metricId + "': run metadata states no samplingInterval. "
                + "The recording interval is required to place metric rows on the run's tick grid.");
        }
        int recordingInterval = resolvedConfig.getInt("samplingInterval");
        if (recordingInterval < 1) {
            throw new IllegalStateException(
                "Metric '" + metricId + "': run metadata states samplingInterval=" + recordingInterval
                + ", which is not a valid recording interval.");
        }
        return recordingInterval;
    }

    /**
     * Computes the absolute tick interval for every LOD level.
     * <p>
     * Formula: {@code recordingInterval * samplingInterval * lodFactor^level}. The recording
     * interval turns the configured value into a count of recorded ticks rather than of
     * simulation ticks, so the same configuration yields the same number of rows per recording
     * regardless of how densely the run was recorded.
     *
     * @param recordingInterval Ticks between two recorded ticks
     * @return Absolute tick interval per LOD level
     * @throws IllegalStateException if an interval exceeds the range of {@code int}
     */
    private int[] computeEffectiveSamplingIntervals(int recordingInterval) {
        int[] intervals = new int[lodLevels];
        for (int level = 0; level < lodLevels; level++) {
            long interval = (long) recordingInterval * samplingInterval;
            for (int i = 0; i < level; i++) {
                interval *= lodFactor;
                if (interval > Integer.MAX_VALUE) break;
            }
            if (interval > Integer.MAX_VALUE) {
                throw new IllegalStateException(
                    "Metric '" + metricId + "': effective sampling interval for lod" + level
                    + " exceeds the supported range (recordingInterval=" + recordingInterval
                    + ", samplingInterval=" + samplingInterval + ", lodFactor=" + lodFactor + ").");
            }
            intervals[level] = (int) interval;
        }
        return intervals;
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
     * {@inheritDoc}
     * <p>
     * Example for a run recording every 100th tick, with samplingInterval=10 and lodFactor=10:
     * <ul>
     *   <li>lod0: every 10th recorded tick = every 1000th tick</li>
     *   <li>lod1: every 100th recorded tick = every 10000th tick</li>
     *   <li>lod2: every 1000th recorded tick = every 100000th tick</li>
     * </ul>
     */
    @Override
    public int getEffectiveSamplingInterval(int level) {
        if (effectiveSamplingIntervals == null) {
            throw new IllegalStateException(
                "Metric '" + metricId + "': sampling intervals are unavailable because the plugin "
                + "was initialized without an analytics context.");
        }
        if (level < 0 || level >= effectiveSamplingIntervals.length) {
            throw new IllegalArgumentException(
                "Metric '" + metricId + "': LOD level " + level + " is outside the configured range 0.."
                + (effectiveSamplingIntervals.length - 1) + ".");
        }
        return effectiveSamplingIntervals[level];
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
    
    /**
     * {@inheritDoc}
     * <p>
     * Applies common configuration (e.g., {@code maxDataPoints}) to all entries
     * returned by {@link #getManifestEntry()} or overridden {@code getManifestEntries()}.
     */
    @Override
    public java.util.List<ManifestEntry> getManifestEntries() {
        ManifestEntry entry = getManifestEntry();
        if (entry == null) {
            return java.util.List.of();
        }
        applyCommonConfig(entry);
        return java.util.List.of(entry);
    }

    /**
     * Applies common configuration fields to a manifest entry.
     * <p>
     * The tick interval of every level is filled in here rather than by each plugin: it follows
     * from the sampling the plugin was configured with, and a reader needs it to know how many
     * points a level holds without opening a single file.
     *
     * @param entry The manifest entry to configure
     */
    protected void applyCommonConfig(ManifestEntry entry) {
        if (maxDataPoints != null) {
            entry.maxDataPoints = maxDataPoints;
        }

        entry.tickIntervals = new java.util.LinkedHashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            entry.tickIntervals.put(lodLevelName(level), getEffectiveSamplingInterval(level));
        }
    }

    // Abstract methods that subclasses MUST implement:
    // - getSchema()
    // - extractRows(TickData)
    // - getManifestEntry()
}

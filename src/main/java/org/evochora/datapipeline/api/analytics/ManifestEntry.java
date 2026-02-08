package org.evochora.datapipeline.api.analytics;

import java.util.Map;

/**
 * Data Transfer Object for Analytics Manifest.
 * <p>
 * Describes a metric exposed to the frontend. Contains all information needed
 * for the frontend to:
 * <ol>
 *   <li>Discover available metrics</li>
 *   <li>Load Parquet data</li>
 *   <li>Execute queries (with computed columns)</li>
 *   <li>Render visualizations</li>
 * </ol>
 * <p>
 * The manifest is generated from plugin definitions and cached by the controller.
 */
public class ManifestEntry {
    
    /** Unique metric identifier (e.g., "vital_stats", "population") */
    public String id;

    /**
     * Storage metric identifier for resolving Parquet file paths.
     * <p>
     * When a single plugin produces multiple manifest entries (e.g., a merged genome plugin
     * producing both a diversity chart and a population chart), all entries share the same
     * underlying Parquet data stored under the plugin's {@code metricId}.
     * <p>
     * The controller uses this field to locate Parquet files: {@code {storageMetricId}/{lod}/...}
     * <p>
     * If {@code null}, the controller falls back to using {@link #id} as the storage prefix
     * (the common case where one plugin = one metric = one manifest entry).
     */
    public String storageMetricId;
    
    /** Human-readable name (e.g., "Birth & Death Rates") */
    public String name;
    
    /** Description of what this metric shows */
    public String description;

    /**
     * Map of LOD levels to source file patterns.
     * <p>
     * Key: "lod0", "lod1", "lod2"
     * Value: Glob pattern e.g., "vital_stats/lod0/**\/*.parquet"
     */
    public Map<String, String> dataSources;

    /**
     * Visualization configuration for the frontend.
     * <p>
     * Defines chart type, axes, and styling options.
     */
    public VisualizationHint visualization;
    
    /**
     * Generated SQL query for client-side DuckDB WASM.
     * <p>
     * Contains the full SELECT statement with computed columns (window functions).
     * The placeholder {@code {table}} is replaced by the actual table/file reference.
     * <p>
     * Example:
     * <pre>
     * SELECT tick,
     *        COALESCE(total_born - LAG(total_born, 1, total_born) OVER (ORDER BY tick), 0) AS births,
     *        COALESCE((LAG(alive_count) OVER (ORDER BY tick) + births) - alive_count, 0) AS deaths
     * FROM {table}
     * ORDER BY tick
     * </pre>
     */
    public String generatedQuery;
    
    /**
     * List of column names in the query output.
     * <p>
     * Used by the frontend to know which columns are available after transformation.
     */
    public java.util.List<String> outputColumns;
    
    /**
     * Maximum number of data points the frontend should load at once (optional).
     * <p>
     * When set, overrides the default pixel-based limit ({@code chartPixelWidth / 2}).
     * Useful for chart types like bar charts where each data point needs more horizontal
     * space, or for metrics with expensive server-side merges where a smaller window
     * reduces load time.
     * <p>
     * If {@code null}, the frontend uses {@code min(5000, chartPixelWidth / 2)}.
     */
    public Integer maxDataPoints;

    /**
     * Path to custom visualizer JavaScript (optional).
     * <p>
     * If set, the frontend loads this script for custom rendering.
     * Relative to the plugin's resource directory.
     */
    public String customVisualizerPath;
}

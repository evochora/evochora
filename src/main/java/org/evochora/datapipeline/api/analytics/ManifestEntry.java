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
     * Path to custom visualizer JavaScript (optional).
     * <p>
     * If set, the frontend loads this script for custom rendering.
     * Relative to the plugin's resource directory.
     */
    public String customVisualizerPath;
}

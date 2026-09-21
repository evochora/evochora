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
    
    /** Human-readable name (e.g., "Birth &amp; Death Rates") */
    public String name;

    /**
     * Group of the analyzer page the card stands on, or {@code null} for the group of cards that
     * name none. Where a card stands is a property of the view, not of the run: the node fills
     * this field, {@link #fullWidth} and {@link #order} from its own configuration when it serves
     * the manifest, and the indexer never writes them.
     */
    public String group;

    /** Whether the card takes a row of the dashboard on its own; {@code null} means it does not. */
    public Boolean fullWidth;

    /** Place of the card among all cards, counted from 0 without gaps; filled by the node like {@link #group}. */
    public Integer order;
    
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

    /**
     * Ticks between two rows, per LOD level.
     * <p>
     * Key: "lod0", "lod1", ... Value: the tick distance between consecutive rows of that level.
     * <p>
     * With the tick range of a metric this gives the exact number of points a level holds, which
     * decides whether a chart can show the whole run at once or has to show a window of it. The
     * frontend would otherwise have to guess that number from how many files were written, and a
     * guess is wrong in the direction that matters: coarse levels write no fewer files, only
     * shorter ones.
     */
    public Map<String, Integer> tickIntervals;

    /**
     * The tables this chart reads alongside its own (optional).
     * <p>
     * A metric whose rows only mean something next to other tables names them here: the frontend
     * loads all of them, runs {@link #generatedQuery} on this entry's data and each companion's
     * query on its own, and hands the chart every result. The clade view uses it to read the
     * population of each genome next to the lineage it descends in and next to the mutation that
     * founded it.
     * <p>
     * Every companion is loaded at its own finest level of detail, not at the level chosen for this
     * metric: a table that carries structure rather than a time series loses its meaning when
     * thinned out.
     * <p>
     * If {@code null} or empty, the chart reads only its own data, which is the common case.
     */
    public java.util.List<Companion> companions;

    /**
     * The columns whose values are counts, added up when a coarser level of detail is built.
     * <p>
     * The browser may not drop rows of such a metric to fit a card: a dropped row takes its counts
     * with it, and the chart would then say that fewer were born than were. It says so here so
     * that a card can refuse rather than undercount.
     */
    public java.util.List<String> summedColumns;

    /**
     * One table a chart reads alongside its own data.
     * <p>
     * A companion is read at the finest level of detail unless it follows the chart's level: a
     * table carrying structure, such as a lineage, must stay complete, while a table carrying
     * values over time can be read as coarsely as the chart itself.
     *
     * @param metricId     storage metric identifier under which the table's Parquet files are found
     * @param query        SQL query for that table, with the same {@code {table}} placeholder
     *                     {@link #generatedQuery} uses
     * @param followsLevel whether the table is read at the level of detail the chart shows
     * @param columnar     whether the frontend hands the chart one array per column instead of one
     *                     object per row; for a table too large to turn into objects, where the
     *                     per-row objects cost more than the values they carry
     */
    public record Companion(String metricId, String query, boolean followsLevel, boolean columnar) {

        /**
         * A companion read at the finest level of detail, row by row.
         *
         * @param metricId storage metric identifier under which the table's Parquet files are found
         * @param query    SQL query for that table
         */
        public Companion(String metricId, String query) {
            this(metricId, query, false, false);
        }

        /**
         * A companion read row by row.
         *
         * @param metricId     storage metric identifier under which the table's Parquet files are
         *                     found
         * @param query        SQL query for that table
         * @param followsLevel whether the table is read at the level of detail the chart shows
         */
        public Companion(String metricId, String query, boolean followsLevel) {
            this(metricId, query, followsLevel, false);
        }
    }
}

package org.evochora.datapipeline.api.analytics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Specification for frontend visualization of analytics data.
 * <p>
 * Defines how the data should be rendered in the browser. The frontend
 * uses this to select the appropriate chart type and configure it.
 * <p>
 * <strong>Standard Chart Types:</strong>
 * <ul>
 *   <li>{@code line-chart} - Time series data</li>
 *   <li>{@code bar-chart} - Categorical/discrete data</li>
 *   <li>{@code stacked-area} - Cumulative/composition data</li>
 *   <li>{@code heatmap} - 2D density/matrix data</li>
 *   <li>{@code band-chart} - Range/percentile data</li>
 * </ul>
 * <p>
 * <strong>Example:</strong>
 * <pre>{@code
 * VisualizerSpec spec = VisualizerSpec.builder()
 *     .chartType("bar-chart")
 *     .xAxis("tick")
 *     .yAxis("births", "deaths")
 *     .option("style", "mirrored")
 *     .option("colors", Map.of("births", "#4ade80", "deaths", "#f87171"))
 *     .build();
 * }</pre>
 */
public class VisualizerSpec {

    private final String chartType;
    private final String xAxis;
    private final String[] yAxis;
    private final String[] y2Axis;
    private final Map<String, Object> options;
    private final String customVisualizerPath;

    private VisualizerSpec(Builder builder) {
        this.chartType = builder.chartType;
        this.xAxis = builder.xAxis;
        this.yAxis = builder.yAxis.toArray(new String[0]);
        this.y2Axis = builder.y2Axis.toArray(new String[0]);
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(builder.options));
        this.customVisualizerPath = builder.customVisualizerPath;
    }

    /**
     * Returns the chart type identifier.
     *
     * @return Chart type (e.g., "line-chart", "bar-chart")
     */
    public String getChartType() {
        return chartType;
    }

    /**
     * Returns the column for the X axis.
     *
     * @return X axis column name
     */
    public String getXAxis() {
        return xAxis;
    }

    /**
     * Returns the columns for the primary Y axis.
     *
     * @return Array of Y axis column names
     */
    public String[] getYAxis() {
        return yAxis;
    }

    /**
     * Returns the columns for the secondary Y axis (if any).
     *
     * @return Array of Y2 axis column names, or empty array
     */
    public String[] getY2Axis() {
        return y2Axis;
    }

    /**
     * Returns additional visualization options.
     *
     * @return Map of option name to value
     */
    public Map<String, Object> getOptions() {
        return options;
    }

    /**
     * Returns the path to a custom visualizer JavaScript file.
     * <p>
     * If set, the frontend will load this script instead of using
     * the standard chart type. The path is relative to the plugin's
     * resource directory.
     *
     * @return Custom visualizer path, or null for standard chart
     */
    public String getCustomVisualizerPath() {
        return customVisualizerPath;
    }

    /**
     * Converts this spec to a VisualizationHint for backward compatibility.
     *
     * @return VisualizationHint with equivalent configuration
     */
    public VisualizationHint toVisualizationHint() {
        VisualizationHint hint = new VisualizationHint();
        hint.type = chartType;
        hint.config = new LinkedHashMap<>();
        hint.config.put("x", xAxis);
        hint.config.put("y", java.util.List.of(yAxis));
        if (y2Axis.length > 0) {
            hint.config.put("y2", java.util.List.of(y2Axis));
        }
        hint.config.putAll(options);
        return hint;
    }

    /**
     * Creates a simple line chart spec.
     *
     * @param xColumn X axis column
     * @param yColumns Y axis columns
     * @return VisualizerSpec for line chart
     */
    public static VisualizerSpec lineChart(String xColumn, String... yColumns) {
        return builder()
            .chartType("line-chart")
            .xAxis(xColumn)
            .yAxis(yColumns)
            .build();
    }

    /**
     * Creates a simple bar chart spec.
     *
     * @param xColumn X axis column
     * @param yColumns Y axis columns
     * @return VisualizerSpec for bar chart
     */
    public static VisualizerSpec barChart(String xColumn, String... yColumns) {
        return builder()
            .chartType("bar-chart")
            .xAxis(xColumn)
            .yAxis(yColumns)
            .build();
    }

    /**
     * Creates a simple table spec (no chart, just tabular data).
     *
     * @return VisualizerSpec for table display
     */
    public static VisualizerSpec table() {
        return builder()
            .chartType("table")
            .build();
    }

    /**
     * Creates a new builder.
     *
     * @return New builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for VisualizerSpec.
     */
    public static class Builder {
        private String chartType = "line-chart";
        private String xAxis = "tick";
        private final java.util.List<String> yAxis = new java.util.ArrayList<>();
        private final java.util.List<String> y2Axis = new java.util.ArrayList<>();
        private final Map<String, Object> options = new LinkedHashMap<>();
        private String customVisualizerPath = null;

        /**
         * Sets the chart type.
         *
         * @param type Chart type identifier
         * @return This builder
         */
        public Builder chartType(String type) {
            this.chartType = type;
            return this;
        }

        /**
         * Sets the X axis column.
         *
         * @param column Column name
         * @return This builder
         */
        public Builder xAxis(String column) {
            this.xAxis = column;
            return this;
        }

        /**
         * Sets the primary Y axis columns.
         *
         * @param columns Column names
         * @return This builder
         */
        public Builder yAxis(String... columns) {
            yAxis.clear();
            for (String col : columns) {
                yAxis.add(col);
            }
            return this;
        }

        /**
         * Sets the secondary Y axis columns.
         *
         * @param columns Column names
         * @return This builder
         */
        public Builder y2Axis(String... columns) {
            y2Axis.clear();
            for (String col : columns) {
                y2Axis.add(col);
            }
            return this;
        }

        /**
         * Adds a visualization option.
         *
         * @param key Option name
         * @param value Option value
         * @return This builder
         */
        public Builder option(String key, Object value) {
            options.put(key, value);
            return this;
        }

        /**
         * Sets the path to a custom visualizer script.
         *
         * @param path Resource path (e.g., "plugins/vitalstats/chart.js")
         * @return This builder
         */
        public Builder customVisualizer(String path) {
            this.customVisualizerPath = path;
            return this;
        }

        /**
         * Builds the VisualizerSpec.
         *
         * @return Immutable VisualizerSpec instance
         */
        public VisualizerSpec build() {
            return new VisualizerSpec(this);
        }
    }
}


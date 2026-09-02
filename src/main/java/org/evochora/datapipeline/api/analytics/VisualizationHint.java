package org.evochora.datapipeline.api.analytics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tells the frontend how to render a metric.
 * <p>
 * The chart types that exist are the ones the browser registers in
 * {@code web/analyzer/js/charts/ChartRegistry.js}; that registry is the only place they are
 * declared, and {@code AnalyticsChartTypesTest} holds every plugin against it. The keys a chart
 * accepts in {@link #config} belong to that chart's own implementation for the same reason: this
 * boundary carries data, and the meaning of a rendering option lives where the rendering happens.
 * <p>
 * {@code x} names the column on the horizontal axis and {@code y} what is drawn against it; both
 * are read by every chart, though the shape of {@code y} follows the chart type. Iteration order
 * reaches the frontend through the JSON, so the map preserves insertion order.
 */
public class VisualizationHint {

    /**
     * The chart type, as registered in the frontend's chart registry.
     */
    public String type;

    /**
     * The options the chart of this type reads, {@code x} and {@code y} among them.
     * <p>
     * Iteration order reaches the frontend through the JSON, so entries appear in the order they
     * were added.
     */
    public Map<String, Object> config;

    /**
     * Describes a chart over a column.
     * <p>
     * The columns drawn against it are an option like any other, because their shape belongs to
     * the chart type: a stacked bar chart reads {@code y2} as one column name, a line chart as a
     * list of them.
     *
     * @param type    a chart type registered in the frontend's chart registry
     * @param xColumn the column on the horizontal axis
     * @return the hint, ready for its options
     */
    public static VisualizationHint chart(String type, String xColumn) {
        VisualizationHint hint = new VisualizationHint();
        hint.type = type;
        hint.config = new LinkedHashMap<>();
        hint.config.put("x", xColumn);
        return hint;
    }

    /**
     * Adds one rendering option, in the order it is added.
     *
     * @param key   an option the chart of this type understands
     * @param value its value
     * @return this hint
     */
    public VisualizationHint with(String key, Object value) {
        config.put(key, value);
        return this;
    }
}

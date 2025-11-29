package org.evochora.datapipeline.api.analytics;

import java.util.Map;

/**
 * Hint for the frontend visualization widget.
 */
public class VisualizationHint {
    public String type; // e.g. "line-chart", "bar-chart", "heatmap"
    
    // Generic configuration map for the widget
    // e.g. { "x": "tick", "y": "count", "color": "blue" }
    public Map<String, Object> config;
}


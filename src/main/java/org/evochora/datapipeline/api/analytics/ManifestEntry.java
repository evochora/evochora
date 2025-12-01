package org.evochora.datapipeline.api.analytics;

import java.util.Map;

/**
 * Data Transfer Object for Manifest.
 * Describes a metric exposed to the frontend.
 */
public class ManifestEntry {
    public String id;           // e.g. "population_stats"
    public String name;         // e.g. "Population Overview"
    public String description;

    // Map of LOD levels to source file patterns
    // Key: "raw", "lod1", "lod2"
    // Value: Glob pattern e.g. "population/lod1/batch_*.parquet"
    public Map<String, String> dataSources;

    public VisualizationHint visualization; // e.g. { type: "line-chart", x: "tick", y: "count" }
}


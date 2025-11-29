package org.evochora.datapipeline.api.analytics;

import com.typesafe.config.Config;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;

import java.util.List;

/**
 * Abstract Base Class for Plugins.
 * Simplifies LOD generation and configuration handling.
 * Subclasses only need to implement `processTick(TickData)` or `processBatch(List<TickData>)` for raw data.
 * The base class handles sampling, LOD aggregation, and file writing.
 */
public abstract class AbstractAnalyticsPlugin implements IAnalyticsPlugin {
    
    protected Config config;
    protected IAnalyticsContext context;
    
    // Standard config
    protected String metricId;
    protected int samplingInterval = 1;
    
    @Override
    public void configure(Config config) {
        this.config = config;
        this.metricId = config.getString("metricId");
        if (config.hasPath("samplingInterval")) {
            this.samplingInterval = config.getInt("samplingInterval");
        }
    }

    @Override
    public void initialize(IAnalyticsContext context) {
        this.context = context;
    }

    @Override
    public void onFinish() {
        // Default: do nothing
    }
    
    // processBatch and getManifestEntry must be implemented by subclass
}


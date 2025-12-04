package org.evochora.datapipeline.services.analytics.plugins;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.analytics.VisualizationHint;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;

/**
 * Tracks birth and death rates over time.
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - Simulation tick number</li>
 *   <li>{@code births} - Number of organisms born in this interval</li>
 *   <li>{@code deaths} - Number of organisms died in this interval</li>
 * </ul>
 * <p>
 * This plugin is stateful: it remembers the previous tick's state (alive count, total born)
 * to calculate the deltas.
 */
public class BirthDeathMetricsPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("births", ColumnType.INTEGER)
        .column("deaths", ColumnType.INTEGER)
        .build();

    private long lastTotalBorn = -1;
    private int lastAliveCount = -1;

    @Override
    public void initialize(IAnalyticsContext context) {
        super.initialize(context);
        this.lastTotalBorn = -1;
        this.lastAliveCount = -1;
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        long currentTotalBorn = tick.getTotalOrganismsCreated();
        int currentAlive = tick.getOrganismsList().size();
        
        // DEBUG: Temporär hinzufügen um Werte zu prüfen
        System.out.println("[DEBUG] BirthDeathMetricsPlugin tick=" + tick.getTickNumber() 
            + " totalBorn=" + currentTotalBorn 
            + " alive=" + currentAlive 
            + " lastTotalBorn=" + lastTotalBorn 
            + " lastAliveCount=" + lastAliveCount);
        
        int births = 0;
        int deaths = 0;
        
        // Calculate deltas if we have history
        if (lastTotalBorn != -1 && lastAliveCount != -1) {
            // Births = Delta in monotonic counter
            births = (int) (currentTotalBorn - lastTotalBorn);
            if (births < 0) births = 0; // Should not happen
            
            // Deaths = (PreviousAlive + Births) - CurrentAlive
            // Balance equation: Current = Prev + Births - Deaths
            deaths = (lastAliveCount + births) - currentAlive;
            if (deaths < 0) deaths = 0; // Should not happen
        }
        
        // Update state
        lastTotalBorn = currentTotalBorn;
        lastAliveCount = currentAlive;
        
        // Return row
        return Collections.singletonList(new Object[] {
            tick.getTickNumber(),
            births,
            deaths
        });
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Birth & Death Rates";
        entry.description = "Organism births (positive) and deaths (negative) per interval.";
        
        // Generate dataSources for all configured LOD levels
        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }
        
        entry.visualization = new VisualizationHint();
        entry.visualization.type = "bar-chart";
        entry.visualization.config = new HashMap<>();
        entry.visualization.config.put("x", "tick");
        // We want to show births as positive and deaths as negative (or separate series)
        // Frontend handles "deaths" usually as is, visualization config can hint stacking/colors.
        entry.visualization.config.put("y", List.of("births", "deaths"));
        // Hint for mirrored chart:
        entry.visualization.config.put("style", "mirrored"); 

        return entry;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // This plugin is stateful but has a tiny, constant memory footprint.
        // It only stores two numbers (lastTotalBorn, lastAliveCount).
        // We report a small constant value to be thorough.
        return Collections.singletonList(new MemoryEstimate(
            "Plugin: " + metricId,
            1024, // ~1 KB for state variables and object overhead
            "Constant state for last total born and alive count",
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}


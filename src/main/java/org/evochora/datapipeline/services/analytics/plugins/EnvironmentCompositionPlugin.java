package org.evochora.datapipeline.services.analytics.plugins;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.analytics.VisualizationHint;
import org.evochora.datapipeline.api.contracts.CellState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.runtime.Config;

/**
 * Tracks the composition of the environment by molecule type.
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - Simulation tick number</li>
 *   <li>{@code code_cells} - Count of cells with CODE molecules</li>
 *   <li>{@code data_cells} - Count of cells with DATA molecules</li>
 *   <li>{@code energy_cells} - Count of cells with ENERGY molecules</li>
 *   <li>{@code structure_cells} - Count of cells with STRUCTURE molecules</li>
 *   <li>{@code empty_cells} - Count of empty cells</li>
 * </ul>
 * <p>
 * This plugin can operate in two modes, configured by {@code monteCarloSamples}:
 * <ul>
 *   <li><b>Exact Mode (default):</b> Iterates all non-empty cells from TickData and calculates empty cells by subtracting from total world size. Accurate but can be slow on dense, large worlds.</li>
 *   <li><b>Sampling Mode:</b> Takes a random sample of N cells from TickData to estimate the distribution. Much faster for very large, dense worlds at the cost of precision.</li>
 * </ul>
 */
public class EnvironmentCompositionPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("code_cells", ColumnType.BIGINT)
        .column("data_cells", ColumnType.BIGINT)
        .column("energy_cells", ColumnType.BIGINT)
        .column("structure_cells", ColumnType.BIGINT)
        .column("empty_cells", ColumnType.BIGINT)
        .build();

    private int monteCarloSamples = 0;

    @Override
    public void configure(com.typesafe.config.Config config) {
        super.configure(config);
        if (config.hasPath("monteCarloSamples")) {
            this.monteCarloSamples = config.getInt("monteCarloSamples");
        }
    }
    
    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        long codeCells = 0;
        long dataCells = 0;
        long energyCells = 0;
        long structureCells = 0;
        long emptyCells = 0;
        long totalCells = 0;

        if (context != null && context.getMetadata() != null && context.getMetadata().hasEnvironment()) {
            totalCells = 1;
            for(int dim : context.getMetadata().getEnvironment().getShapeList()) {
                totalCells *= dim;
            }
        }

        List<CellState> cellsToProcess = new java.util.ArrayList<>(tick.getCellsList());
        
        if (monteCarloSamples > 0 && cellsToProcess.size() > monteCarloSamples) {
            // Sampling mode
            Collections.shuffle(cellsToProcess);
            cellsToProcess = cellsToProcess.subList(0, monteCarloSamples);
            // In sampling mode, we cannot reliably calculate empty cells, so we report -1
            emptyCells = -1; 
        } else {
            // Exact mode
            emptyCells = totalCells > 0 ? totalCells - cellsToProcess.size() : 0;
        }

        for (CellState cell : cellsToProcess) {
            // Molecule types are bitmasked integers
            if (cell.getMoleculeType() == Config.TYPE_CODE) {
                // CODE:0 is considered "empty" program space, not a functional molecule here
                if (cell.getMoleculeValue() != 0 || cell.getOwnerId() != 0) {
                     codeCells++;
                } else {
                    // This is an explicitly transmitted "empty" cell (owner=0, value=0)
                    // It was counted as non-empty by the sender, so we add it to our empty count.
                    if (monteCarloSamples == 0) emptyCells++;
                }
            } else if (cell.getMoleculeType() == Config.TYPE_DATA) {
                dataCells++;
            } else if (cell.getMoleculeType() == Config.TYPE_ENERGY) {
                energyCells++;
            } else if (cell.getMoleculeType() == Config.TYPE_STRUCTURE) {
                structureCells++;
            }
        }
        
        if (monteCarloSamples > 0) {
            // Extrapolate counts
            double factor = (double) tick.getCellsList().size() / cellsToProcess.size();
            codeCells = (long)(codeCells * factor);
            dataCells = (long)(dataCells * factor);
            energyCells = (long)(energyCells * factor);
            structureCells = (long)(structureCells * factor);
        }

        return Collections.singletonList(new Object[]{
            tick.getTickNumber(),
            codeCells,
            dataCells,
            energyCells,
            structureCells,
            emptyCells
        });
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Environment Composition";
        entry.description = "Distribution of molecule types in the environment.";
        
        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }
        
        entry.visualization = new VisualizationHint();
        entry.visualization.type = "stacked-area-chart";
        entry.visualization.config = new HashMap<>();
        entry.visualization.config.put("x", "tick");
        entry.visualization.config.put("y", List.of("code_cells", "data_cells", "energy_cells", "structure_cells", "empty_cells"));

        return entry;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // This plugin is stateless. All calculations are done within the extractRows method
        // and no data is stored between ticks. Therefore, its heap memory usage is negligible.
        return Collections.emptyList();
    }
}

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
        .column("unknown_cells", ColumnType.BIGINT)
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
        long unknownCells = 0;
        long totalCells = 0;

        if (context != null && context.getMetadata() != null && context.getMetadata().hasEnvironment()) {
            totalCells = 1;
            for (int dim : context.getMetadata().getEnvironment().getShapeList()) {
                totalCells *= dim;
            }
        }

        // Direct access to the underlying list - NO COPYING to ArrayList
        List<CellState> allCells = tick.getCellsList();
        int cellsAvailable = allCells.size();
        
        if (monteCarloSamples > 0 && cellsAvailable > monteCarloSamples) {
            // Sampling mode: Random sample without copying
            java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
            long[] counts = new long[5]; // code, data, energy, structure, unknown

            for (int i = 0; i < monteCarloSamples; i++) {
                int index = random.nextInt(cellsAvailable);
                countCell(allCells.get(index), counts);
            }

            // Extrapolate from sample to full cellsAvailable
            double factor = (double) cellsAvailable / monteCarloSamples;
            codeCells = (long) (counts[0] * factor);
            dataCells = (long) (counts[1] * factor);
            energyCells = (long) (counts[2] * factor);
            structureCells = (long) (counts[3] * factor);
            unknownCells = (long) (counts[4] * factor);
        } else {
            // Exact mode: count all non-empty cells
            long[] counts = new long[5];
            for (CellState cell : allCells) {
                countCell(cell, counts);
        }
            codeCells = counts[0];
            dataCells = counts[1];
            energyCells = counts[2];
            structureCells = counts[3];
            unknownCells = counts[4];
        }

        // Empty = total world size - all categorized cells
        // This includes: truly empty cells (not in TickData) + CODE:0 cells
        long emptyCells = Math.max(0, totalCells - codeCells - dataCells - energyCells - structureCells - unknownCells);

        return Collections.singletonList(new Object[]{
            tick.getTickNumber(),
            codeCells,
            dataCells,
            energyCells,
            structureCells,
            unknownCells,
            emptyCells
        });
    }

    /**
     * Counts a cell into the appropriate category.
     * <p>
     * Categories:
     * <ul>
     *   <li>CODE with value != 0 → code_cells</li>
     *   <li>CODE with value == 0 → not counted (empty regardless of owner)</li>
     *   <li>DATA → data_cells</li>
     *   <li>ENERGY → energy_cells</li>
     *   <li>STRUCTURE → structure_cells</li>
     *   <li>Unknown type → unknown_cells</li>
     * </ul>
     *
     * @param cell The cell to count
     * @param counts Array: [code, data, energy, structure, unknown]
     */
    private void countCell(CellState cell, long[] counts) {
        int type = cell.getMoleculeType();
        if (type == Config.TYPE_CODE) {
            // CODE:0 is empty space (regardless of owner)
            if (cell.getMoleculeValue() != 0) {
                counts[0]++;
            }
            // CODE:0 not counted → will be part of emptyCells
        } else if (type == Config.TYPE_DATA) {
            counts[1]++;
        } else if (type == Config.TYPE_ENERGY) {
            counts[2]++;
        } else if (type == Config.TYPE_STRUCTURE) {
            counts[3]++;
        } else {
            counts[4]++; // Unknown type
        }
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
        entry.visualization.config.put("y", List.of("code_cells", "data_cells", "energy_cells", "structure_cells", "unknown_cells", "empty_cells"));
        entry.visualization.config.put("yAxisMode", "percent");

        return entry;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // This plugin is stateless. All calculations are done within the extractRows method
        // and no data is stored between ticks. Therefore, its heap memory usage is negligible.
        return Collections.emptyList();
    }
}

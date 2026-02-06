package org.evochora.datapipeline.services.analytics.plugins;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.analytics.VisualizationHint;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.datapipeline.utils.MetadataConfigHelper;
import org.evochora.datapipeline.utils.MoleculeDataUtils;
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
 *   <li>{@code label_cells} - Count of cells with LABEL molecules (jump targets)</li>
 *   <li>{@code labelref_cells} - Count of cells with LABELREF molecules (jump operands)</li>
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
        .column("label_cells", ColumnType.BIGINT)
        .column("labelref_cells", ColumnType.BIGINT)
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

    /**
     * This plugin requires environment data to count molecule types.
     * <p>
     * The indexer uses this to optimize decompression: environment data is only
     * reconstructed for ticks where this plugin (or another environment-aware plugin)
     * needs to run.
     *
     * @return {@code true} - this plugin analyzes cell composition
     */
    @Override
    public boolean needsEnvironmentData() {
        return true;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        long codeCells = 0;
        long dataCells = 0;
        long energyCells = 0;
        long structureCells = 0;
        long labelCells = 0;
        long labelrefCells = 0;
        long unknownCells = 0;
        long totalCells = 0;

        if (context != null && context.getMetadata() != null && !context.getMetadata().getResolvedConfigJson().isEmpty()) {
            totalCells = 1;
            for (int dim : MetadataConfigHelper.getEnvironmentShape(context.getMetadata())) {
                totalCells *= dim;
            }
        }

        // Direct access to the underlying list - NO COPYING to ArrayList
        CellDataColumns columns = tick.getCellColumns();
        int cellsAvailable = columns.getFlatIndicesCount();

        if (monteCarloSamples > 0 && cellsAvailable > monteCarloSamples) {
            // Sampling mode: Random sample without copying
            java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
            long[] counts = new long[7]; // code, data, energy, structure, label, labelref, unknown

            for (int i = 0; i < monteCarloSamples; i++) {
                int index = random.nextInt(cellsAvailable);
                countCell(columns.getMoleculeData(index), counts);
            }

            // Extrapolate from sample to full cellsAvailable
            double factor = (double) cellsAvailable / monteCarloSamples;
            codeCells = (long) (counts[0] * factor);
            dataCells = (long) (counts[1] * factor);
            energyCells = (long) (counts[2] * factor);
            structureCells = (long) (counts[3] * factor);
            labelCells = (long) (counts[4] * factor);
            labelrefCells = (long) (counts[5] * factor);
            unknownCells = (long) (counts[6] * factor);
        } else {
            // Exact mode: count all non-empty cells
            long[] counts = new long[7];
            for (int i = 0; i < cellsAvailable; i++) {
                countCell(columns.getMoleculeData(i), counts);
            }
            codeCells = counts[0];
            dataCells = counts[1];
            energyCells = counts[2];
            structureCells = counts[3];
            labelCells = counts[4];
            labelrefCells = counts[5];
            unknownCells = counts[6];
        }

        // Empty = total world size - all categorized cells
        // This includes: truly empty cells (not in TickData) + CODE:0 cells
        long emptyCells = Math.max(0, totalCells - codeCells - dataCells - energyCells - structureCells - labelCells - labelrefCells - unknownCells);

        return Collections.singletonList(new Object[]{
            tick.getTickNumber(),
            codeCells,
            dataCells,
            energyCells,
            structureCells,
            labelCells,
            labelrefCells,
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
     *   <li>LABEL → label_cells (fuzzy jump targets)</li>
     *   <li>LABELREF → labelref_cells (jump operands)</li>
     *   <li>Unknown type → unknown_cells</li>
     * </ul>
     *
     * @param moleculeInt The packed molecule integer
     * @param counts Array: [code, data, energy, structure, label, labelref, unknown]
     */
    private void countCell(int moleculeInt, long[] counts) {
        int type = moleculeInt & Config.TYPE_MASK;

        if (type == Config.TYPE_CODE) {
            // CODE:0 is empty space (regardless of owner)
            if (MoleculeDataUtils.extractSignedValue(moleculeInt) != 0) {
                counts[0]++;
            }
            // CODE:0 not counted → will be part of emptyCells
        } else if (type == Config.TYPE_DATA) {
            counts[1]++;
        } else if (type == Config.TYPE_ENERGY) {
            counts[2]++;
        } else if (type == Config.TYPE_STRUCTURE) {
            counts[3]++;
        } else if (type == Config.TYPE_LABEL) {
            counts[4]++; // LABEL molecules (fuzzy jump targets)
        } else if (type == Config.TYPE_LABELREF) {
            counts[5]++; // LABELREF molecules (jump operands)
        } else {
            counts[6]++; // Unknown type
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
        entry.visualization.config.put("y", List.of("code_cells", "data_cells", "energy_cells", "structure_cells", "label_cells", "labelref_cells", "unknown_cells", "empty_cells"));
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

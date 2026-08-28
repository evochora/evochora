package org.evochora.datapipeline.services.analytics.plugins;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.analytics.VisualizationHint;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.delta.ICellStateSource;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Molecule;

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
 * Counts every occupied cell of the tick, walking the environment state directly. Empty cells
 * are the remainder of the world size, which also covers CODE:0 cells held by an organism.
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
        .column("register_cells", ColumnType.BIGINT)
        .column("unknown_cells", ColumnType.BIGINT)
        .column("empty_cells", ColumnType.BIGINT)
        .build();

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
        throw new IllegalStateException("Metric '" + getMetricId() + "' reads the environment and "
                + "must be given one: call extractRows(TickData, ICellStateSource).");
    }

    /**
     * {@inheritDoc}
     * <p>
     * Walks the occupied cells once and sorts each into its molecule category. Reading the state
     * directly avoids building a cell-column message that would be discarded right after.
     */
    @Override
    public List<Object[]> extractRows(TickData tick, ICellStateSource cells) {
        long[] counts = new long[8];
        cells.forEachOccupiedCell((flatIndex, moleculeData, ownerId) -> countCell(moleculeData, counts));

        long categorized = 0;
        for (long count : counts) {
            categorized += count;
        }
        // Empty covers both never-filled cells and CODE:0 cells an organism owns
        long emptyCells = Math.max(0, cells.getTotalCells() - categorized);

        return Collections.singletonList(new Object[]{
            tick.getTickNumber(),
            counts[0],
            counts[1],
            counts[2],
            counts[3],
            counts[4],
            counts[5],
            counts[6],
            counts[7],
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
     *   <li>REGISTER → register_cells (register operands)</li>
     *   <li>Unknown type → unknown_cells</li>
     * </ul>
     *
     * @param moleculeInt The packed molecule integer
     * @param counts Array: [code, data, energy, structure, label, labelref, register, unknown]
     */
    private void countCell(int moleculeInt, long[] counts) {
        int type = moleculeInt & Config.TYPE_MASK;

        if (type == Config.TYPE_CODE) {
            // CODE:0 is empty space (regardless of owner)
            if (Molecule.extractSignedValue(moleculeInt) != 0) {
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
        } else if (type == Config.TYPE_REGISTER) {
            counts[6]++; // REGISTER molecules (register operands)
        } else {
            counts[7]++; // Unknown type
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
        entry.visualization.config.put("y", List.of("code_cells", "data_cells", "energy_cells", "structure_cells", "label_cells", "labelref_cells", "register_cells", "unknown_cells"));
        entry.visualization.config.put("yAxisMode", "percent");
        entry.visualization.config.put("percentBase", List.of("code_cells", "data_cells", "energy_cells", "structure_cells", "label_cells", "labelref_cells", "register_cells", "unknown_cells", "empty_cells"));

        return entry;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // This plugin is stateless. All calculations are done within the extractRows method
        // and no data is stored between ticks. Therefore, its heap memory usage is negligible.
        return Collections.emptyList();
    }
}

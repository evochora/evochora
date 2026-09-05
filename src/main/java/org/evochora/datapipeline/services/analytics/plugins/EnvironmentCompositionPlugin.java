package org.evochora.datapipeline.services.analytics.plugins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

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
import org.evochora.runtime.model.MoleculeTypeRegistry;

/**
 * Tracks the composition of the environment by molecule type.
 * <p>
 * <strong>Metrics:</strong> {@code tick}, one {@code <type>_cells} column per molecule type
 * registered in {@link MoleculeTypeRegistry}, in registration order — currently
 * {@code code_cells}, {@code data_cells}, {@code energy_cells}, {@code structure_cells},
 * {@code label_cells}, {@code labelref_cells}, {@code register_cells}, {@code state_cells} —
 * followed by {@code unknown_cells} for a molecule whose type the registry does not know and
 * {@code empty_cells} for the rest of the world.
 * <p>
 * Counts every occupied cell of the tick, walking the environment state directly. Empty cells
 * are the remainder of the world size, which also covers CODE:0 cells held by an organism.
 */
public class EnvironmentCompositionPlugin extends AbstractAnalyticsPlugin {

    /**
     * The count column for each registered molecule type, in registration order, followed by the
     * column for molecules of an unregistered type. Its size is the width of the count array, and
     * the position of a column is the count slot it is filled from.
     */
    private static final List<String> COUNT_COLUMNS = buildCountColumns();

    /**
     * Count slot per raw type index. A molecule whose index this array does not cover, or whose
     * slot is the unknown slot, is counted as unknown.
     */
    private static final int[] SLOT_BY_RAW_INDEX = buildSlotByRawIndex();

    /** Count slot for a molecule whose type is not registered. */
    private static final int UNKNOWN_SLOT = COUNT_COLUMNS.size() - 1;

    private static final ParquetSchema SCHEMA = buildSchema();

    /**
     * Builds the column name of every count slot: {@code <lowercased type name>_cells} for each
     * registered type in registration order, then {@code unknown_cells}.
     *
     * @return The count column names, in count-slot order.
     */
    private static List<String> buildCountColumns() {
        List<String> columns = new ArrayList<>();
        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            columns.add(MoleculeTypeRegistry.typeToName(type).toLowerCase(Locale.ROOT) + "_cells");
        }
        columns.add("unknown_cells");
        return Collections.unmodifiableList(columns);
    }

    /**
     * Builds the lookup from a molecule's raw type index to its count slot.
     *
     * @return An array whose entry is the count slot of that raw type index, unknown where the
     *         index belongs to no registered type.
     */
    private static int[] buildSlotByRawIndex() {
        int highest = 0;
        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            highest = Math.max(highest, rawIndex(type));
        }
        int[] slots = new int[highest + 1];
        Arrays.fill(slots, MoleculeTypeRegistry.typeCount());
        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            slots[rawIndex(type)] = MoleculeTypeRegistry.indexOf(type);
        }
        return slots;
    }

    /**
     * Builds the Parquet schema: the tick, one column per count slot, then the empty count.
     *
     * @return The schema of this metric's rows.
     */
    private static ParquetSchema buildSchema() {
        ParquetSchema.Builder builder = ParquetSchema.builder().column("tick", ColumnType.BIGINT);
        for (String column : COUNT_COLUMNS) {
            builder.column(column, ColumnType.BIGINT);
        }
        return builder.column("empty_cells", ColumnType.BIGINT).build();
    }

    /**
     * Returns the position of a molecule type's bits within the packed molecule integer, shifted
     * down to a small index.
     *
     * @param type The shifted type constant.
     * @return The raw type index.
     */
    private static int rawIndex(int type) {
        return (type & Config.TYPE_MASK) >>> Config.TYPE_SHIFT;
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
        long[] counts = new long[COUNT_COLUMNS.size()];
        cells.forEachOccupiedCell((flatIndex, moleculeData, ownerId) -> countCell(moleculeData, counts));

        long categorized = 0;
        for (long count : counts) {
            categorized += count;
        }
        // Empty covers both never-filled cells and CODE:0 cells an organism owns
        long emptyCells = Math.max(0, cells.getTotalCells() - categorized);

        Object[] row = new Object[counts.length + 2];
        row[0] = tick.getTickNumber();
        for (int slot = 0; slot < counts.length; slot++) {
            row[slot + 1] = counts[slot];
        }
        row[row.length - 1] = emptyCells;
        return Collections.singletonList(row);
    }

    /**
     * Counts a cell into the slot of its molecule type.
     * <p>
     * A CODE molecule with value 0 is empty space regardless of its owner and is counted nowhere,
     * so it falls into the empty remainder. A molecule whose type is not registered is counted as
     * unknown.
     *
     * @param moleculeInt The packed molecule integer
     * @param counts The count array, one slot per entry of {@link #COUNT_COLUMNS}
     */
    private void countCell(int moleculeInt, long[] counts) {
        int type = moleculeInt & Config.TYPE_MASK;

        if (type == Config.TYPE_CODE && Molecule.extractSignedValue(moleculeInt) == 0) {
            return;
        }

        int index = rawIndex(type);
        counts[index < SLOT_BY_RAW_INDEX.length ? SLOT_BY_RAW_INDEX[index] : UNKNOWN_SLOT]++;
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

        List<String> percentBase = new ArrayList<>(COUNT_COLUMNS);
        percentBase.add("empty_cells");
        entry.visualization = VisualizationHint.chart("stacked-area-chart", "tick")
            .with("y", COUNT_COLUMNS)
            .with("yAxisMode", "percent")
            .with("percentBase", percentBase);

        return entry;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // This plugin is stateless. All calculations are done within the extractRows method
        // and no data is stored between ticks. Therefore, its heap memory usage is negligible.
        return Collections.emptyList();
    }
}

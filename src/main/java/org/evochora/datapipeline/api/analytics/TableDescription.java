package org.evochora.datapipeline.api.analytics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a table of a run holds, in the words its plugin gave it: the description that travels with
 * the data.
 * <p>
 * The indexer writes one per plugin next to the table's files, the node lists them for a run and
 * puts the same texts into every Parquet file it hands out. A reader therefore learns what a
 * column means from the run it reads, whichever build wrote it and whichever plugins it ran with.
 *
 * @param id            Storage metric identifier of the table, under which its files are found
 * @param description   What the table holds and what one row of it is
 * @param tickIntervals Ticks between two rows for each level of detail the table has, by level
 *                      name ("lod0", "lod1", ...), finest first
 * @param columns       The columns, in the order they stand in a row
 */
public record TableDescription(String id, String description, Map<String, Integer> tickIntervals,
                               List<Column> columns) {

    /** Name of the file a table's description is written to, next to the table's files. */
    public static final String FILE_NAME = "table.json";

    /** Key of the table's description among the key-value metadata of a Parquet file. */
    public static final String TABLE_KEY = "description";

    /** Prefix of a column's key among the key-value metadata of a Parquet file; the column's name follows. */
    public static final String COLUMN_KEY_PREFIX = "description.";

    /**
     * One column of a table.
     *
     * @param name        Column name
     * @param type        SQL type of its values
     * @param summed      Whether a row of a coarser level adds up the rows of its window; a column
     *                    that is not summed keeps the value of the recorded tick the row stands on
     * @param description What a value of the column says, with its unit where it has one
     */
    public record Column(String name, String type, boolean summed, String description) {
    }

    /**
     * Describes the table a plugin writes.
     *
     * @param plugin A plugin that knows its sampling, which it does once it has been initialized
     *               with an analytics context
     * @return The description of its table
     */
    public static TableDescription of(IAnalyticsPlugin plugin) {
        ParquetSchema schema = plugin.getSchema();
        Map<String, Integer> tickIntervals = new LinkedHashMap<>();
        for (int level = 0; level < plugin.getLodLevels(); level++) {
            tickIntervals.put(AbstractAnalyticsPlugin.lodLevelName(level), plugin.getEffectiveSamplingInterval(level));
        }
        List<Column> columns = new ArrayList<>(schema.getColumnCount());
        for (ParquetSchema.Column column : schema.getColumns()) {
            columns.add(new Column(column.name(), column.type().getSqlType(),
                column.aggregation() == Aggregation.SUM, column.description()));
        }
        return new TableDescription(plugin.getMetricId(), schema.getDescription(), tickIntervals, columns);
    }

    /**
     * The descriptions as the key-value metadata of a Parquet file: the table's under
     * {@link #TABLE_KEY}, each column's under {@link #COLUMN_KEY_PREFIX} followed by its name.
     *
     * @return The keys and their texts, the table first and the columns in their order
     */
    public Map<String, String> asKeyValueMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(TABLE_KEY, description);
        for (Column column : columns) {
            metadata.put(COLUMN_KEY_PREFIX + column.name(), column.description());
        }
        return metadata;
    }
}

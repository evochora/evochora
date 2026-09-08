package org.evochora.datapipeline.services.analytics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.Aggregation;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.contracts.TickData;

import com.typesafe.config.Config;

/**
 * An analytics plugin whose rows are known from the tick alone, for the indexer tests.
 * <p>
 * A recording at tick {@code t} produces the row {@code (t, t, t * 100)}: {@code events} is a
 * summed column, so a coarser level has to carry the sum of its window, and {@code alive} is a
 * sampled one, so it has to carry the value of the recording the level's row stands on. Both are
 * derived from the tick, which makes every expected number in a test a sum or a value the test can
 * name.
 * <p>
 * Two options bend the plugin into the shapes the indexer has to cope with:
 * <ul>
 *   <li>{@code skipMultiplesOf} - recordings whose tick is a multiple of this produce no row at
 *       all, the way a metric of events reports nothing for a window in which none happened</li>
 *   <li>{@code rowsPerRecording} - how many rows one recording produces, which is a configuration
 *       error above one for a plugin with summed columns</li>
 * </ul>
 */
public class SummedCountsTestPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("events", ColumnType.INTEGER, Aggregation.SUM)
        .column("alive", ColumnType.INTEGER)
        .build();

    private int skipMultiplesOf;
    private int rowsPerRecording = 1;

    @Override
    public void configure(Config config) {
        super.configure(config);
        if (config.hasPath("skipMultiplesOf")) {
            this.skipMultiplesOf = config.getInt("skipMultiplesOf");
        }
        if (config.hasPath("rowsPerRecording")) {
            this.rowsPerRecording = config.getInt("rowsPerRecording");
        }
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        long tickNumber = tick.getTickNumber();
        if (skipMultiplesOf > 0 && tickNumber % skipMultiplesOf == 0) {
            return Collections.emptyList();
        }
        List<Object[]> rows = new ArrayList<>(rowsPerRecording);
        for (int i = 0; i < rowsPerRecording; i++) {
            rows.add(new Object[] { tickNumber, (int) tickNumber, (int) tickNumber * 100 });
        }
        return rows;
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Counted Events";
        entry.description = "Events per time window, for the indexer tests.";
        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }
        return entry;
    }
}

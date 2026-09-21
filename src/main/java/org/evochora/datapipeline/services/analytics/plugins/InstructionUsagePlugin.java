package org.evochora.datapipeline.services.analytics.plugins;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.analytics.VisualizationHint;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.memory.MemoryEstimate;
import org.evochora.datapipeline.api.memory.SimulationParameters;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Instruction.InstructionInfo;

import com.typesafe.config.Config;

/**
 * Tracks the usage of different instruction families over time.
 * <p>
 * This plugin dynamically discovers instruction families from the runtime and
 * stores raw counts per tick. The query aggregates data into one point per window for
 * visualization as a stacked bar chart with percentage normalization.
 * <p>
 * Additionally tracks instruction failure rates on a secondary Y-axis,
 * showing the percentage of executed instructions that failed.
 * <p>
 * Below the shares, the chart shows the failed instructions in absolute numbers, split by
 * instruction, with the failure texts in the tooltip. It reads them from the table
 * {@link InstructionFailuresPlugin} writes, as a companion that follows the chart's level of
 * detail.
 * <p>
 * <strong>Design:</strong>
 * <ul>
 *   <li>Stateless: only extracts current tick's instruction counts</li>
 *   <li>Dynamic: instruction families discovered at startup</li>
 *   <li>Bucket aggregation: one window per point the card draws</li>
 *   <li>Percentage mode: each bar totals 100%</li>
 *   <li>Failure rate: secondary line showing % failed instructions</li>
 * </ul>
 */
public class InstructionUsagePlugin extends AbstractAnalyticsPlugin {

    /** Largest number of instructions the failure half names; the rest is one group. */
    private static final int FAILURE_GROUPS = 8;

    /** Metric holding the failed instructions and their failure texts. */
    private String failuresMetricId = "instruction_failures";

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code failuresMetricId} is configured empty
     */
    @Override
    public void configure(Config config) {
        super.configure(config);
        this.failuresMetricId = companionMetricId(config, "failuresMetricId",
            "the failed instructions shown below the shares", failuresMetricId);
    }

    private static final Map<Integer, String> OPCODE_TO_FAMILY_NAME = new HashMap<>();
    private static final List<String> FAMILY_NAMES;
    private static final ParquetSchema SCHEMA;

    static {
        // Dynamically discover all instruction families from the Instruction class.
        List<InstructionInfo> instructionSet = Instruction.getInstructionSetInfo();

        Map<Class<?>, String> familyClassToSimpleName = instructionSet.stream()
            .map(InstructionInfo::family)
            .distinct()
            .collect(Collectors.toMap(
                familyClass -> familyClass,
                familyClass -> familyClass.getSimpleName().replace("Instruction", "").toLowerCase()
            ));

        for (InstructionInfo info : instructionSet) {
            OPCODE_TO_FAMILY_NAME.put(info.opcodeId(), familyClassToSimpleName.get(info.family()));
        }

        FAMILY_NAMES = familyClassToSimpleName.values().stream().sorted().collect(Collectors.toList());
        
        // Build schema dynamically from the discovered family names
        ParquetSchema.Builder builder = ParquetSchema.builder();
        builder.column("tick", ColumnType.BIGINT);
        for (String familyName : FAMILY_NAMES) {
            builder.column(familyName, ColumnType.INTEGER);
        }
        // Track failed instruction count for failure rate calculation
        builder.column("failure_count", ColumnType.INTEGER);
        SCHEMA = builder.build();
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        Map<String, Integer> counts = FAMILY_NAMES.stream().collect(Collectors.toMap(name -> name, name -> 0));
        int failureCount = 0;

        for (var org : tick.getOrganismsList()) {
            if (org.getIsDead()) continue;
            if (org.hasInstructionOpcodeId()) {
                String familyName = OPCODE_TO_FAMILY_NAME.get(org.getInstructionOpcodeId());
                if (familyName != null) {
                    counts.compute(familyName, (k, v) -> (v == null) ? 1 : v + 1);
                }
                // Count failed instructions (only those that attempted execution)
                if (org.getInstructionFailed()) {
                    failureCount++;
                }
            }
        }

        // Row: tick, [family counts...], failure_count
        Object[] row = new Object[FAMILY_NAMES.size() + 2];
        row[0] = tick.getTickNumber();
        for (int i = 0; i < FAMILY_NAMES.size(); i++) {
            row[i + 1] = counts.get(FAMILY_NAMES.get(i));
        }
        row[FAMILY_NAMES.size() + 1] = failureCount;

        return Collections.singletonList(row);
    }

    /**
     * Generates the aggregated SQL query with dynamic bucket sizing.
     * <p>
     * The query aggregates instruction counts into one point per window, summing
     * all counts per family within each bucket. Percentage normalization
     * is done client-side by the chart component.
     * <p>
     * For failure rate, calculates the maximum rate within each bucket
     * and records the tick where that maximum occurred. Every row also carries the bucket size,
     * so the chart can place the rows of its failure companion in the same buckets.
     *
     * @return SQL query string with {table} placeholder
     */
    private String generateAggregatedQuery() {
        // Build column list dynamically from discovered families
        String sumColumns = FAMILY_NAMES.stream()
            .map(name -> "SUM(" + name + ")::BIGINT AS " + name)
            .collect(Collectors.joining(",\n                "));

        // Build total calculation from all family columns (for per-tick rate)
        String totalExpr = FAMILY_NAMES.stream()
            .map(name -> name)
            .collect(Collectors.joining(" + "));

        return """
            WITH
            %s,
            per_tick AS (
                SELECT
                    tick,
                    %s AS bucket_tick,
                    CASE
                        WHEN (%s) = 0 THEN 0.0
                        ELSE (failure_count::DOUBLE * 100.0 / (%s))
                    END AS tick_failure_rate,
                    %s
                FROM {table}
            )
            SELECT
                bucket_tick AS tick,
                %s,
                MAX(tick_failure_rate) AS failure_rate,
                ARG_MAX(tick, tick_failure_rate) AS failure_rate_peak_tick,
                ANY_VALUE((SELECT bucket_size FROM params)) AS bucket_size
            FROM per_tick
            GROUP BY 1
            ORDER BY tick
            """.formatted(windowParams(), windowTick(), totalExpr, totalExpr,
                         FAMILY_NAMES.stream().collect(Collectors.joining(", ")),
                         sumColumns);
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Instruction Usage";
        entry.description = "Instruction families (%) with peak failure rate, and failed instructions "
            + "below (sampled).";

        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }
        
        // Use aggregated query with bucketing
        entry.generatedQuery = generateAggregatedQuery();

        // Output columns: tick + all family names + failure_rate + peak_tick
        List<String> outputCols = new java.util.ArrayList<>();
        outputCols.add("tick");
        outputCols.addAll(FAMILY_NAMES);
        outputCols.add("failure_rate");
        outputCols.add("failure_rate_peak_tick");
        outputCols.add("bucket_size");
        entry.outputColumns = outputCols;

        // The failures arrive as written, one row per instruction and text per recording: grouping
        // them by text is a hash aggregation the browser's DuckDB build does not survive, so the
        // chart sums them itself
        entry.companions = List.of(new ManifestEntry.Companion(failuresMetricId,
            "SELECT tick, instruction, reason, count FROM {table}", true));

        entry.visualization = VisualizationHint.chart("stacked-bar-chart", "tick")
            .with("y", FAMILY_NAMES)
            .with("yAxisMode", "percent")
            // A stacked bar chart reads y2 as one column name, not as a list.
            .with("y2", "failure_rate")
            .with("y2Label", "Peak failure rate")
            .with("y2PeakTick", "failure_rate_peak_tick")
            .with("lower", Map.of(
                "metric", failuresMetricId,
                "group", "instruction",
                "detail", "reason",
                "value", "count",
                "bucketSize", "bucket_size",
                "label", "Failures",
                "maxGroups", FAILURE_GROUPS));

        return entry;
    }

    @Override
    public List<MemoryEstimate> estimateWorstCaseMemory(SimulationParameters params) {
        // The main state (opcode -> family mapping) is static and initialized once.
        // Its size depends on the number of instructions, not simulation parameters.
        // The per-tick `counts` map is transient.
        // Therefore, the heap impact is constant and very small.
        return Collections.singletonList(new MemoryEstimate(
            "Plugin: " + metricId,
            8192, // ~8 KB for static instruction family maps
            "Constant static state for instruction family opcode mapping",
            MemoryEstimate.Category.SERVICE_BATCH
        ));
    }
}

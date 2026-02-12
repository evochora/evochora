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

/**
 * Tracks the usage of different instruction families over time.
 * <p>
 * This plugin dynamically discovers instruction families from the runtime and
 * stores raw counts per tick. The query aggregates data into ~100 buckets for
 * visualization as a stacked bar chart with percentage normalization.
 * <p>
 * Additionally tracks instruction failure rates on a secondary Y-axis,
 * showing the percentage of executed instructions that failed.
 * <p>
 * <strong>Design:</strong>
 * <ul>
 *   <li>Stateless: only extracts current tick's instruction counts</li>
 *   <li>Dynamic: instruction families discovered at startup</li>
 *   <li>Bucket aggregation: ~100 time buckets for readable visualization</li>
 *   <li>Percentage mode: each bar totals 100%</li>
 *   <li>Failure rate: secondary line showing % failed instructions</li>
 * </ul>
 */
public class InstructionUsagePlugin extends AbstractAnalyticsPlugin {
    
    /** Target number of buckets for aggregation (~100 bars in chart) */
    private static final int TARGET_BUCKETS = 100;

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
     * The query aggregates instruction counts into ~100 buckets, summing
     * all counts per family within each bucket. Percentage normalization
     * is done client-side by the chart component.
     * <p>
     * For failure rate, calculates the maximum rate within each bucket
     * and records the tick where that maximum occurred.
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
            params AS (
                SELECT GREATEST(1, (MAX(tick) - MIN(tick)) / %d)::BIGINT AS bucket_size
                FROM {table}
            ),
            per_tick AS (
                SELECT
                    tick,
                    (FLOOR(tick / (SELECT bucket_size FROM params)) * (SELECT bucket_size FROM params))::BIGINT AS bucket_tick,
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
                ARG_MAX(tick, tick_failure_rate) AS failure_rate_peak_tick
            FROM per_tick
            GROUP BY 1
            ORDER BY tick
            """.formatted(TARGET_BUCKETS, totalExpr, totalExpr,
                         FAMILY_NAMES.stream().collect(Collectors.joining(", ")),
                         sumColumns);
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Instruction Usage";
        entry.description = "Instruction family breakdown (%) with maximum failure rate (sampled).";

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
        entry.outputColumns = outputCols;

        entry.visualization = new VisualizationHint();
        entry.visualization.type = "stacked-bar-chart";
        entry.visualization.config = new HashMap<>();
        entry.visualization.config.put("x", "tick");
        entry.visualization.config.put("y", FAMILY_NAMES);
        entry.visualization.config.put("yAxisMode", "percent");
        // Secondary Y-axis for failure rate (line overlay)
        entry.visualization.config.put("y2", "failure_rate");
        entry.visualization.config.put("y2Label", "Failure Rate");
        entry.visualization.config.put("y2PeakTick", "failure_rate_peak_tick");

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

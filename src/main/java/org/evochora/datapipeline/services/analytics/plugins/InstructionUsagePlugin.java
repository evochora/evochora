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
 * This plugin dynamically discovers instruction families from the runtime.
 */
public class InstructionUsagePlugin extends AbstractAnalyticsPlugin {

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
        SCHEMA = builder.build();
    }

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    @Override
    public List<Object[]> extractRows(TickData tick) {
        Map<String, Integer> counts = FAMILY_NAMES.stream().collect(Collectors.toMap(name -> name, name -> 0));

        tick.getOrganismsList().forEach(org -> {
            if (org.hasInstructionOpcodeId()) {
                String familyName = OPCODE_TO_FAMILY_NAME.get(org.getInstructionOpcodeId());
                if (familyName != null) {
                    counts.compute(familyName, (k, v) -> (v == null) ? 1 : v + 1);
                }
            }
        });
        
        Object[] row = new Object[FAMILY_NAMES.size() + 1];
        row[0] = tick.getTickNumber();
        for (int i = 0; i < FAMILY_NAMES.size(); i++) {
            row[i + 1] = counts.get(FAMILY_NAMES.get(i));
        }

        return Collections.singletonList(row);
    }

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId;
        entry.name = "Instruction Usage";
        entry.description = "Distribution of executed instruction families per tick.";

        entry.dataSources = new HashMap<>();
        for (int level = 0; level < lodLevels; level++) {
            String lodName = lodLevelName(level);
            entry.dataSources.put(lodName, metricId + "/" + lodName + "/**/*.parquet");
        }

        entry.visualization = new VisualizationHint();
        entry.visualization.type = "stacked-area-chart";
        entry.visualization.config = new HashMap<>();
        entry.visualization.config.put("x", "tick");
        entry.visualization.config.put("y", FAMILY_NAMES);
        entry.visualization.config.put("yAxisMode", "percent");

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

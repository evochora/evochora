package org.evochora.datapipeline.services.analytics.plugins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ColumnType;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.ParquetSchema;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Instruction.InstructionInfo;

/**
 * Counts failed instructions by instruction and by the reason the runtime gave.
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 *   <li>{@code tick} - the recording</li>
 *   <li>{@code instruction} - name of the instruction that failed</li>
 *   <li>{@code reason} - the failure text exactly as the runtime wrote it</li>
 *   <li>{@code count} - living organisms whose instruction failed that way in this recording</li>
 * </ul>
 * <p>
 * A recording holds the last instruction of each organism, so the counts are a sample of what
 * fails, in the same way the instruction usage chart samples what runs. An organism reports the
 * first failure of its tick.
 * <p>
 * The failure text carries values - a register id, a vector's dimensions - so one instruction can
 * fail with many different texts. The table keeps them as they are; condensing them is left to
 * whoever reads it.
 * <p>
 * The plugin is stateless: each row depends on its recording alone. It has no chart of its own;
 * the instruction usage chart reads it as a companion.
 */
public class InstructionFailuresPlugin extends AbstractAnalyticsPlugin {

    private static final ParquetSchema SCHEMA = ParquetSchema.builder()
        .column("tick", ColumnType.BIGINT)
        .column("instruction", ColumnType.VARCHAR)
        .column("reason", ColumnType.VARCHAR)
        .column("count", ColumnType.INTEGER)
        .build();

    /** Instruction name per opcode. */
    private static final Map<Integer, String> OPCODE_TO_NAME = new HashMap<>();

    static {
        for (InstructionInfo info : Instruction.getInstructionSetInfo()) {
            OPCODE_TO_NAME.put(info.opcodeId(), info.name());
        }
    }

    /** One way an instruction failed. */
    private record Failure(String instruction, String reason) { }

    /** Reused across ticks; holds the failures of the current recording in first-seen order. */
    private final Map<Failure, Integer> failuresInTick = new LinkedHashMap<>();

    @Override
    public ParquetSchema getSchema() {
        return SCHEMA;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns one row per instruction and failure text seen in the recording, and no row when
     * nothing failed.
     */
    @Override
    public List<Object[]> extractRows(TickData tick) {
        failuresInTick.clear();
        for (OrganismState org : tick.getOrganismsList()) {
            if (org.getIsDead() || !org.getInstructionFailed() || !org.hasInstructionOpcodeId()) {
                continue;
            }
            int opcode = org.getInstructionOpcodeId();
            String name = OPCODE_TO_NAME.getOrDefault(opcode, "opcode " + opcode);
            failuresInTick.merge(new Failure(name, org.getFailureReason()), 1, Integer::sum);
        }

        List<Object[]> rows = new ArrayList<>(failuresInTick.size());
        for (Map.Entry<Failure, Integer> entry : failuresInTick.entrySet()) {
            rows.add(new Object[] {
                tick.getTickNumber(),
                entry.getKey().instruction(),
                entry.getKey().reason(),
                entry.getValue()
            });
        }
        return rows;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The table has no chart of its own: the instruction usage chart shows it below its shares.
     */
    @Override
    public ManifestEntry getManifestEntry() {
        return null;
    }
}

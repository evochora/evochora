package org.evochora.server.contracts;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Fully prepared, JSON-ready snapshot for a single tick.
 * <p>
 * This object is produced by the backend and consumed by the web frontend without
 * additional client-side transformation. It contains only plain data (no HTML).
 */
public record PreparedTickState(
        int schemaVersion,
        String mode,
        String generatedAtUtc,
        long tickNumber,
        WorldMeta worldMeta,
        WorldState worldState,
        Map<String, OrganismDetails> organismDetails
) implements IQueueMessage {

    /** Convenience constructor filling generatedAtUtc with now and schemaVersion=1. */
    public static PreparedTickState of(String mode,
                                       long tickNumber,
                                       WorldMeta worldMeta,
                                       WorldState worldState,
                                       Map<String, OrganismDetails> organismDetails) {
        return new PreparedTickState(1, mode, Instant.now().toString(), tickNumber, worldMeta, worldState, organismDetails);
    }

    /** Metadata about the world/grid. */
    public record WorldMeta(int[] shape) {}

    /** Sparse world state for a tick. */
    public record WorldState(List<Cell> cells, List<OrganismBasic> organisms) {}

    /** Single cell snapshot. For CODE type, opcodeName is provided. */
    public record Cell(List<Integer> position, String type, int value, int ownerId, String opcodeName) {}

    /** Minimal organism info used by the world renderer overlay. */
    public record OrganismBasic(int id, String programId, List<Integer> position, long energy, List<List<Integer>> dps, List<Integer> dv) {}

    /**
     * Per-organism detailed view, keyed by organism id as string.
     */
    public record OrganismDetails(BasicInfo basicInfo,
                                  NextInstruction nextInstruction,
                                  InternalState internalState,
                                  SourceView sourceView) {}

    /** Basic organism metadata. */
    public record BasicInfo(int id, String programId, Integer parentId, long birthTick,
                             long energy, List<Integer> position, List<Integer> direction) {}

    /** Next instruction summary. Operands use fully written types (DATA/CODE/ENERGY/STRUCTURE). */
    public record NextInstruction(String disassembly, String sourceFile, Integer sourceLine) {}

    /** Registers and stacks. Values use short prefixes (D/C/E/S). */
    public record InternalState(List<RegisterValue> dataRegisters,
                                List<RegisterValue> procRegisters,
                                List<String> dataStack,
                                List<String> callStack,
                                List<String> formalParameters,
                                List<String> fprs,
                                List<String> locationRegisters,
                                List<String> locationStack,
                                List<List<Integer>> dps) {}

    /** Register with optional alias and formatted value. */
    public record RegisterValue(String id, String alias, String value) {}

    /** Source view data without HTML. Present only in debug mode. */
    public record SourceView(String fileName, Integer currentLine, List<SourceLine> lines, List<InlineSpan> inlineValues) {}

    /** A single source line. */
    public record SourceLine(int number, String content, boolean isCurrent) {}

    /** Inline value span for annotation (1-based columns). Kind may be 'reg','define','jump','callParam','popParam'. */
    public record InlineSpan(int lineNumber, int startColumn, int length, String text, String kind) {}
}



package org.evochora.server.contracts.debug;

import java.util.List;
import java.util.Map;

/**
 * Top-level container for all prepared debug data for a single tick.
 * This is the object that gets serialized to JSON and represents the final,
 * enriched data contract for any debug client.
 */
public record PreparedTickState(
        String mode, // "debug" or "performance"
        long tickNumber,
        WorldMeta worldMeta,
        WorldState worldState,
        Map<Integer, OrganismDetails> organismDetails // Using Integer key for organism ID
) {
    /** Static metadata about the world. */
    public record WorldMeta(int[] shape) {}

    /** Data for the visual representation of the world grid. */
    public record WorldState(
            List<Cell> cells,
            List<OrganismBasic> organisms
    ) {}

    /** Represents a single cell in the world, prepared for display. */
    public record Cell(List<Integer> position, String type, int value, int ownerId, String opcodeName) {}

    /** Represents a basic summary of an organism for world view rendering. */
    public record OrganismBasic(int id, String programId, List<Integer> position, long energy, List<List<Integer>> dps, List<Integer> dv) {}

    /** Contains all details for a single organism, typically displayed in a sidebar. */
    public record OrganismDetails(
            BasicInfo basicInfo,
            NextInstruction nextInstruction,
            InternalState internalState,
            SourceView sourceView
    ) {}

    /** Basic identifying information for an organism. */
    public record BasicInfo(int id, String programId, Integer parentId, long birthTick, long energy, List<Integer> ip, List<Integer> dv) {}

    /** Information about the next instruction to be executed. */
    public record NextInstruction(String disassembly, String sourceFile, Integer sourceLine, String runtimeStatus) {} // e.g., runtimeStatus = "OK" or "CODE_MISMATCH"

    /** The complete internal state (registers, stacks) of an organism, formatted for display. */
    public record InternalState(
            List<RegisterValue> dataRegisters,
            List<RegisterValue> procRegisters,
            List<RegisterValue> locationRegisters,
            List<String> dataStack,
            List<String> locationStack,
            List<String> callStack,
            List<List<Integer>> dps
    ) {}

    /** A single register's value, formatted for display. */
    public record RegisterValue(String id, String alias, String value) {}

    /** Represents the annotated source code view. */
    public record SourceView(
            String fileName,
            int currentLine,
            List<SourceLine> lines,
            List<InlineSpan> inlineSpans
    ) {}

    /** A single line of source code. */
    public record SourceLine(int number, String content, boolean isCurrent) {}

    /** A single piece of runtime information injected into a source line for annotation. */
    public record InlineSpan(int lineNumber, int startColumn, String text, String kind) {} // kind: "register", "jump", "call-param"
}

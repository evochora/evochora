package org.evochora.server.contracts.debug;

import org.evochora.server.contracts.IQueueMessage;
import java.util.List;
import java.util.Map;

public record PreparedTickState(
        String mode,
        long tickNumber,
        WorldMeta worldMeta,
        WorldState worldState,
        Map<String, OrganismDetails> organismDetails
) implements IQueueMessage {

    public record WorldMeta(int[] shape) {}

    public record WorldState(
            List<Cell> cells,
            List<OrganismBasic> organisms
    ) {}

    public record Cell(List<Integer> position, String type, int value, int ownerId, String opcodeName) {}
    public record OrganismBasic(int id, String programId, List<Integer> position, long energy, List<List<Integer>> dps, List<Integer> dv) {}

    public record OrganismDetails(
            BasicInfo basicInfo,
            NextInstruction nextInstruction,
            InternalState internalState,
            SourceView sourceView
    ) {}

    public record BasicInfo(int id, String programId, Integer parentId, long birthTick, long energy, List<Integer> ip, List<Integer> dv) {}

    // GEÄNDERT: runtimeStatus hinzugefügt
    public record NextInstruction(String disassembly, String sourceFile, Integer sourceLine, String runtimeStatus) {}

    public record InternalState(
            List<RegisterValue> dataRegisters,
            List<RegisterValue> procRegisters,
            List<RegisterValue> fpRegisters,
            List<RegisterValue> locationRegisters,
            List<String> dataStack,
            List<String> locationStack,
            List<CallStackEntry> callStack,
            List<List<Integer>> dps
    ) {}

    public record RegisterValue(String id, String alias, String value) {}

    public record CallStackEntry(
            String procName,
            int[] returnCoordinates,
            List<ParameterBinding> parameters
    ) {}

    public record ParameterBinding(
            int drId,
            String value,
            String paramName
    ) {}

    public record SourceView(
            String fileName,
            Integer currentLine,
            List<SourceLine> lines,
            List<InlineSpan> inlineSpans
    ) {}

    // GEÄNDERT: injectedProlog und injectedEpilog hinzugefügt
    public record SourceLine(
            int number,
            String content,
            boolean isCurrent,
            List<String> injectedProlog,
            List<String> injectedEpilog
    ) {}

    // GEÄNDERT: token-basierte Struktur
    public record InlineSpan(
            int lineNumber,
            String tokenToAnnotate,
            int occurrence,
            String annotationText,
            String kind
    ) {}
}
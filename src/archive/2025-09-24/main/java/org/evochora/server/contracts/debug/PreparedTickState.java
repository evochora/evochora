package org.evochora.server.contracts.debug;

import org.evochora.server.contracts.IQueueMessage;
import com.fasterxml.jackson.annotation.JsonInclude;
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
            @JsonInclude(JsonInclude.Include.NON_NULL) SourceView sourceView
    ) {}

    public record BasicInfo(int id, String programId, Integer parentId, long birthTick, long energy, List<Integer> ip, List<Integer> dv) {}

    public record NextInstruction(
            int opcodeId,
            String opcodeName, 
            List<Object> arguments,
            List<String> argumentTypes,
            List<int[]> argPositions,
            LastExecutionStatus lastExecutionStatus
    ) {}

    public record LastExecutionStatus(
            String status,  // "SUCCESS", "FAILED", "CONFLICT_LOST"
            String failureReason  // nur wenn status = "FAILED"
    ) {}

    public record InternalState(
            List<RegisterValue> dataRegisters,
            List<RegisterValue> procRegisters,
            List<RegisterValue> fpRegisters,
            List<RegisterValue> locationRegisters,
            List<String> dataStack,
            List<String> locationStack,
            List<CallStackEntry> callStack,
            List<List<Integer>> dps,
            int activeDpIndex
    ) {}

    public record RegisterValue(String id, String alias, String value) {}

    public record CallStackEntry(
            String procName,
            int[] returnCoordinates,
            List<ParameterBinding> parameters,
            Map<Integer, Integer> fprBindings  // NEU: Rohe FPR-Bindings für rekursive Auflösung
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
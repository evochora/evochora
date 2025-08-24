package org.evochora.server.indexer;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.server.contracts.debug.PreparedTickState.InlineSpan;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.SerializableProcFrame;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Encapsulates the logic for creating source code annotations (InlineSpans).
 */
public class SourceAnnotator {

    /**
     * Creates all annotations for a given source line based on the organism's current state.
     * @param o The raw state of the organism.
     * @param artifact The program artifact containing metadata.
     * @param sourceLine The content of the source code line.
     * @param lineNumber The line number.
     * @return A list of InlineSpan objects.
     */
    public List<InlineSpan> annotate(RawOrganismState o, ProgramArtifact artifact, String sourceLine, int lineNumber) {
        if (artifact == null || sourceLine == null) {
            return Collections.emptyList();
        }

        List<InlineSpan> spans = new ArrayList<>();
        String[] tokens = sourceLine.trim().split("\\s+");
        Map<String, Integer> tokenOccurrences = new HashMap<>();

        for (String token : tokens) {
            // Remove trailing colon for labels, but keep original token for matching
            String cleanToken = token.endsWith(":") ? token.substring(0, token.length() - 1) : token;
            int occurrence = tokenOccurrences.compute(token, (k, v) -> (v == null) ? 1 : v + 1);

            // Rule A: Annotate labels and procedure names in CALLs
            annotateJumpTargets(spans, token, cleanToken, occurrence, lineNumber, artifact);

            // Rule B: Annotate register aliases and procedure parameters
            annotateRegistersAndAliases(spans, token, cleanToken, occurrence, lineNumber, o, artifact);
        }
        return spans;
    }

    private void annotateJumpTargets(List<InlineSpan> spans, String originalToken, String cleanToken, int occurrence, int lineNumber, ProgramArtifact artifact) {
        Integer targetAddress = artifact.labelAddressToName().entrySet().stream()
                .filter(entry -> cleanToken.equalsIgnoreCase(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);

        if (targetAddress != null) {
            int[] coord = artifact.linearAddressToCoord().get(targetAddress);
            if (coord != null) {
                spans.add(new InlineSpan(lineNumber, originalToken, occurrence, formatVector(coord), "jump"));
            }
        }
    }

    private void annotateRegistersAndAliases(List<InlineSpan> spans, String originalToken, String cleanToken, int occurrence, int lineNumber, RawOrganismState o, ProgramArtifact artifact) {
        String canonicalReg = resolveToCanonicalRegister(cleanToken, o, artifact);

        if (canonicalReg != null) {
            int regId = resolveRegisterNameToId(canonicalReg);
            Object rawValue = readOperand(o, regId);
            String valueStr = formatValue(rawValue);
            String annotation = String.format("[%s=%s]", canonicalReg, valueStr);
            spans.add(new InlineSpan(lineNumber, originalToken, occurrence, annotation, "reg"));
        }
    }

    private String resolveToCanonicalRegister(String token, RawOrganismState o, ProgramArtifact artifact) {
        String upperToken = token.toUpperCase();

        // Check global aliases first
        if (artifact.registerAliasMap() != null) {
            Integer regId = artifact.registerAliasMap().get(upperToken);
            if (regId != null) {
                return "%DR" + regId; // Assuming global aliases only point to DRs for now
            }
        }

        // Check procedure parameters by walking the call stack
        if (o.callStack() != null && !o.callStack().isEmpty()) {
            SerializableProcFrame topFrame = o.callStack().peek();
            List<String> paramNames = artifact.procNameToParamNames().get(topFrame.procName().toUpperCase());
            if (paramNames != null) {
                int paramIndex = -1;
                for (int i = 0; i < paramNames.size(); i++) {
                    if (paramNames.get(i).equalsIgnoreCase(upperToken)) {
                        paramIndex = i;
                        break;
                    }
                }

                if (paramIndex != -1) {
                    // Resolve the binding chain
                    int finalRegId = resolveBindingChain(o.callStack(), paramIndex);
                    return idToCanonicalName(finalRegId);
                }
            }
        }
        return null;
    }

    private int resolveBindingChain(Deque<SerializableProcFrame> callStack, int initialFprIndex) {
        List<SerializableProcFrame> frames = new ArrayList<>(callStack);
        int currentRegId = Instruction.FPR_BASE + initialFprIndex;

        for (int i = 0; i < frames.size(); i++) {
            SerializableProcFrame frame = frames.get(i);
            Integer mappedId = frame.fprBindings().get(currentRegId);
            if (mappedId != null) {
                currentRegId = mappedId;
                if (currentRegId < Instruction.FPR_BASE) {
                    return currentRegId; // Found a DR or PR
                }
            } else {
                // End of chain
                break;
            }
        }
        return currentRegId;
    }

    private String idToCanonicalName(int regId) {
        if (regId >= Instruction.FPR_BASE) return "%FPR" + (regId - Instruction.FPR_BASE);
        if (regId >= Instruction.PR_BASE) return "%PR" + (regId - Instruction.PR_BASE);
        if (regId >= 0) return "%DR" + regId;
        return "INVALID";
    }

    private int resolveRegisterNameToId(String canonicalName) {
        if (canonicalName == null) return -1;
        if (canonicalName.startsWith("%DR")) return Integer.parseInt(canonicalName.substring(3));
        if (canonicalName.startsWith("%PR")) return Instruction.PR_BASE + Integer.parseInt(canonicalName.substring(3));
        if (canonicalName.startsWith("%FPR")) return Instruction.FPR_BASE + Integer.parseInt(canonicalName.substring(4));
        return -1;
    }

    private Object readOperand(RawOrganismState o, int regId) {
        if (regId >= Instruction.FPR_BASE) {
            int index = regId - Instruction.FPR_BASE;
            return (o.fprs() != null && index < o.fprs().size()) ? o.fprs().get(index) : null;
        }
        if (regId >= Instruction.PR_BASE) {
            int index = regId - Instruction.PR_BASE;
            return (o.prs() != null && index < o.prs().size()) ? o.prs().get(index) : null;
        }
        if (regId >= 0 && o.drs() != null && regId < o.drs().size()) {
            return o.drs().get(regId);
        }
        return null;
    }

    private String formatVector(int[] vector) {
        if (vector == null) return "[]";
        return "[" + Arrays.stream(vector).mapToObj(String::valueOf).collect(Collectors.joining("|")) + "]";
    }

    private String formatValue(Object obj) {
        if (obj instanceof Integer i) {
            Molecule m = Molecule.fromInt(i);
            return String.format("%s:%d", typeIdToName(m.type()), m.toScalarValue());
        } else if (obj instanceof int[] v) {
            return formatVector(v);
        }
        return "null";
    }

    private String typeIdToName(int typeId) {
        if (typeId == Config.TYPE_CODE) return "CODE";
        if (typeId == Config.TYPE_DATA) return "DATA";
        if (typeId == Config.TYPE_ENERGY) return "ENERGY";
        if (typeId == Config.TYPE_STRUCTURE) return "STRUCTURE";
        return "UNKNOWN";
    }
}
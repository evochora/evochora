// src/main/java/org/evochora/Logger.java
package org.evochora;

import org.evochora.assembler.*;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

public class Logger {

    private final World world;
    private final Simulation simulation;

    public Logger(World world, Simulation simulation) {
        this.world = world;
        this.simulation = simulation;
    }

    public String getSourceLocationString(Organism organism, int[] absoluteCoord) {
        String programId = AssemblyProgram.getProgramIdForOrganism(organism);
        if (programId == null) return "";
        ProgramMetadata metadata = AssemblyProgram.getMetadataForProgram(programId);
        if (metadata == null || metadata.relativeCoordToLinearAddress() == null || metadata.linearAddressToSourceLocation() == null) return "";

        int[] programOrigin = organism.getInitialPosition();
        int[] relativeCoord = new int[absoluteCoord.length];
        for (int i = 0; i < absoluteCoord.length; i++) {
            relativeCoord[i] = absoluteCoord[i] - programOrigin[i];
        }

        List<Integer> coordAsList = Arrays.stream(relativeCoord).boxed().collect(Collectors.toList());
        Integer linearAddress = metadata.relativeCoordToLinearAddress().get(coordAsList);
        if (linearAddress == null) return "";

        SourceLocation location = metadata.linearAddressToSourceLocation().get(linearAddress);
        if (location == null) return "";

        return String.format(" [%s:%d] > %s", location.fileName(), location.lineNumber(), location.lineContent().strip());
    }

    public String getNextInstructionInfo(Organism organism) {
        if (organism.isDead()) {
            return "DEAD";
        }

        DisassembledInstruction disassembled = AssemblyProgram.getDisassembledInstructionDetailsForNextTick(organism);
        String programId = AssemblyProgram.getProgramIdForOrganism(organism);
        ProgramMetadata metadata = (programId != null) ? AssemblyProgram.getMetadataForProgram(programId) : null;

        StringBuilder info = new StringBuilder();
        if (disassembled != null && disassembled.opcodeName() != null && !disassembled.opcodeName().equals("N/A")) {
            info.append(disassembled.opcodeName());
            if (!disassembled.arguments().isEmpty()) {
                info.append("(");
                for (int i = 0; i < disassembled.arguments().size(); i++) {
                    if (i > 0) info.append(", ");
                    info.append(getResolvedArgumentString(organism, disassembled.arguments().get(i), metadata));
                }
                info.append(")");
            }
        }
        return info.toString();
    }

    public void logTickDetails(Organism organism, Instruction plannedAction) {
        if (!organism.isLoggingEnabled()) {
            return;
        }

        StringBuilder logLine = new StringBuilder();
        logLine.append(String.format("Tick %-5d | ORG ID %-3d | ", simulation.getCurrentTick(), organism.getId()));
        logLine.append("ER: ").append(String.format("%-5d", organism.getEr()));
        logLine.append(" | IP: ").append(formatCoordinate(organism.getIpBeforeFetch()));
        logLine.append(" | DP: ").append(formatCoordinate(organism.getDp()));
        logLine.append(" | DV: ").append(formatCoordinate(organism.getDvBeforeFetch()));

        logLine.append(" | DRs: ");
        List<Object> drs = organism.getDrs();
        for (int i = 0; i < drs.size(); i++) {
            Object drVal = drs.get(i);
            String valStr = formatDrValue(drVal);
            logLine.append(String.format("%d=%s", i, valStr));
            if (i < drs.size() - 1) logLine.append(", ");
        }

        logLine.append(" | PRs: ");
        List<Object> prs = organism.getPrs();
        for (int i = 0; i < prs.size(); i++) {
            Object prVal = prs.get(i);
            String valStr = formatDrValue(prVal);
            logLine.append(String.format("%d=%s", i, valStr));
            if (i < prs.size() - 1) logLine.append(", ");
        }

        logLine.append(" | FPRs: ");
        List<Object> fprs = organism.getFprs();
        for (int i = 0; i < fprs.size(); i++) {
            Object fprVal = fprs.get(i);
            String valStr = formatDrValue(fprVal);
            logLine.append(String.format("%d=%s", i, valStr));
            if (i < fprs.size() - 1) logLine.append(", ");
        }

        logLine.append(" | DS: ").append(formatStack(organism, false, 0));
        logLine.append(" | RS: ").append(formatReturnStack(organism, false, 0));

        DisassembledInstruction disassembled = AssemblyProgram.getDisassembledInstructionDetailsForLastTick(organism);
        String plannedInstructionInfo = "N/A";
        if (disassembled != null) {
            plannedInstructionInfo = disassembled.opcodeName();
            if (disassembled.arguments() != null && !disassembled.arguments().isEmpty()) {
                String programId = AssemblyProgram.getProgramIdForOrganism(organism);
                ProgramMetadata metadata = (programId != null) ? AssemblyProgram.getMetadataForProgram(programId) : null;
                String args = disassembled.arguments().stream()
                        .map(arg -> getResolvedArgumentString(organism, arg, metadata))
                        .collect(Collectors.joining(", "));
                plannedInstructionInfo += "(" + args + ")";
            }
        }
        logLine.append(" | Planned: ").append(plannedInstructionInfo);


        if (plannedAction.isExecutedInTick()) {
            logLine.append(" | Executed: YES");
            if (organism.isInstructionFailed()) {
                logLine.append(" | Failed: YES (").append(organism.getFailureReason()).append(")");
            } else {
                logLine.append(" | Failed: NO");
            }
        } else {
            logLine.append(" | Executed: NO");
            if (plannedAction.getConflictStatus() != Instruction.ConflictResolutionStatus.NOT_APPLICABLE) {
                logLine.append(" | Conflict: YES (Reason: ").append(plannedAction.getConflictStatus()).append(")");
            }
        }

        String sourceInfo = getSourceLocationString(organism, organism.getIpBeforeFetch());
        if (!sourceInfo.isEmpty()) {
            logLine.append(" |").append(sourceInfo);
        }

        System.out.println(logLine.toString());
    }

    public String formatStack(Organism organism, boolean truncate, int limit) {
        Deque<Object> stack = organism.getDataStack();
        if (stack.isEmpty()) {
            return String.format("(0/%d): []", Config.STACK_MAX_DEPTH);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("(%d/%d): [", stack.size(), Config.STACK_MAX_DEPTH));
        Iterator<Object> it = stack.iterator();
        int count = 0;
        while (it.hasNext()) {
            if (count > 0) sb.append(", ");
            if (truncate && count >= limit) {
                sb.append("...");
                break;
            }
            sb.append(formatDrValue(it.next()));
            count++;
        }
        sb.append("]");
        return sb.toString();
    }

    public String formatReturnStack(Organism organism, boolean truncate, int limit) {
        Deque<Object> rs = organism.getReturnStack();
        if (rs.isEmpty()) {
            return String.format("(0/%d): []", Config.RS_MAX_DEPTH);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("(%d/%d): [", rs.size(), Config.RS_MAX_DEPTH));
        Iterator<Object> it = rs.iterator();
        int count = 0;
        while (it.hasNext()) {
            if (count > 0) sb.append(", ");
            if (truncate && count >= limit) {
                sb.append("...");
                break;
            }
            Object entry = it.next();
            if (entry instanceof Organism.ProcFrame f) {
                sb.append("RET:").append(formatCoordinate(f.relativeReturnIp));
            } else {
                sb.append(formatDrValue(entry));
            }
            count++;
        }
        sb.append("]");
        return sb.toString();
    }


    private String getResolvedArgumentString(Organism organism, DisassembledArgument argument, ProgramMetadata metadata) {
        if (argument.type() == ArgumentType.REGISTER && metadata != null && metadata.registerIdToName() != null) {
            String regAlias = argument.resolvedValue();
            int regId = argument.rawValue();
            String regName = String.format("DR%d", regId);
            Object regValue = organism.getDr(regId);
            String formattedValue = formatDrValue(regValue);

            if (regAlias != null && !regAlias.isEmpty()) {
                return String.format("%s[%s]=%s", regAlias, regName, formattedValue);
            }
            return String.format("%s=%s", regName, formattedValue);
        }
        return argument.resolvedValue();
    }

    public String formatCoordinate(int[] coord) {
        if (coord == null) return "null";
        return "[" + Arrays.stream(coord).mapToObj(String::valueOf).collect(Collectors.joining("|")) + "]";
    }

    public String formatDrValue(Object drVal) {
        if (drVal == null) return "null";
        if (drVal instanceof int[] vec) return "V:" + formatCoordinate(vec);
        if (drVal instanceof Integer i) {
            Symbol symbol = Symbol.fromInt(i);
            String typePrefix = switch (symbol.type()) {
                case Config.TYPE_CODE -> "C";
                case Config.TYPE_DATA -> "D";
                case Config.TYPE_ENERGY -> "E";
                case Config.TYPE_STRUCTURE -> "S";
                default -> "?";
            };
            return typePrefix + ":" + symbol.value();
        }
        return drVal.toString();
    }
}

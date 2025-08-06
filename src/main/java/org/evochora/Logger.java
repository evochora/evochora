// src/main/java/org/evochora/Logger.java
package org.evochora;

import org.evochora.assembler.ArgumentType;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.assembler.AssemblyProgram;
import org.evochora.assembler.DisassembledInstruction;
import org.evochora.assembler.ProgramMetadata;
import org.evochora.assembler.Disassembler;
import org.evochora.assembler.DisassembledArgument;

import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Logger {

    private final World world;
    private final Simulation simulation;
    private final Disassembler disassembler = new Disassembler();

    public Logger(World world, Simulation simulation) {
        this.world = world;
        this.simulation = simulation;
    }

    public String getNextInstructionInfo(Organism organism) {
        if (organism.isDead()) {
            return "DEAD";
        }

        DisassembledInstruction disassembledInstruction = AssemblyProgram.getDisassembledInstructionDetailsForNextTick(organism);
        String programId = AssemblyProgram.getProgramIdForOrganism(organism);
        ProgramMetadata metadata = (programId != null) ? AssemblyProgram.getMetadataForProgram(programId) : null;

        StringBuilder info = new StringBuilder();
        if (disassembledInstruction != null && disassembledInstruction.opcodeName() != null && !disassembledInstruction.opcodeName().equals("N/A")) {
            info.append(disassembledInstruction.opcodeName());
            if (!disassembledInstruction.arguments().isEmpty()) {
                info.append("(");
                for (int i = 0; i < disassembledInstruction.arguments().size(); i++) {
                    if (i > 0) info.append(", ");
                    var argument = disassembledInstruction.arguments().get(i);
                    info.append(getResolvedArgumentString(organism, argument, metadata));
                }
                info.append(")");
            }
            if (disassembledInstruction.resolvedTargetCoordinate() != null && !disassembledInstruction.resolvedTargetCoordinate().isEmpty()) {
                info.append(" -> ").append(disassembledInstruction.resolvedTargetCoordinate());
            }
        } else {
            // Fallback
        }
        return info.toString();
    }

    /**
     * Loggt die Details für das Log (Vergangenheit) - mit erweiterter Argument-Anzeige.
     */
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

        // NEU: Fügt den UNGEKÜRZTEN Stack zum Log hinzu
        logLine.append(" | Stack: ").append(formatStack(organism, false, 0));

        logLine.append(" | Planned: ");

        DisassembledInstruction disassembledInstruction = AssemblyProgram.getDisassembledInstructionDetailsForLastTick(organism);
        String programId = AssemblyProgram.getProgramIdForOrganism(organism);
        ProgramMetadata metadata = (programId != null) ? AssemblyProgram.getMetadataForProgram(programId) : null;

        if (disassembledInstruction != null && disassembledInstruction.opcodeName() != null && !disassembledInstruction.opcodeName().equals("N/A")) {
            logLine.append(disassembledInstruction.opcodeName());

            if (!disassembledInstruction.arguments().isEmpty()) {
                logLine.append("(");
                for (int i = 0; i < disassembledInstruction.arguments().size(); i++) {
                    if (i > 0) logLine.append(", ");
                    var argument = disassembledInstruction.arguments().get(i);
                    logLine.append(getResolvedArgumentString(organism, argument, metadata));
                }
                logLine.append(")");
            }

            if (disassembledInstruction.resolvedTargetCoordinate() != null && !disassembledInstruction.resolvedTargetCoordinate().isEmpty()) {
                logLine.append(" -> ").append(disassembledInstruction.resolvedTargetCoordinate());
            }
            logLine.append(" (Type: ").append(disassembledInstruction.instructionType()).append(")");
        } else {
            logLine.append(formatGenericAction(organism, plannedAction));
        }

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
            } else {
                logLine.append(" | Reason: Unknown (not executed, no conflict status)");
            }
        }

        System.out.println(logLine.toString());
    }

    // NEU: Eine flexible Methode zur Formatierung des Stacks
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
            if (count > 0) {
                sb.append(", ");
            }

            if (truncate && count >= limit) {
                sb.append("...");
                break;
            }

            Object value = it.next();
            sb.append(formatDrValue(value)); // Wir können die existierende Logik wiederverwenden!
            count++;
        }
        sb.append("]");
        return sb.toString();
    }

    private String getResolvedArgumentString(Organism organism, DisassembledArgument argument, ProgramMetadata metadata) {
        StringBuilder sb = new StringBuilder();

        // Register-Namen anzeigen, falls vorhanden
        if (argument.type() == ArgumentType.REGISTER && metadata != null) {
            String regName = metadata.registerIdToName().get(argument.rawValue());
            if (regName != null) {
                // NEU: Fügt die Register-ID in Klammern hinzu, z.B. %VEC_RIGHT[4]=...
                sb.append(String.format("%s[%d]=", regName, argument.rawValue()));
            } else {
                // Fallback, falls kein Name für die ID existiert
                sb.append(String.format("%%DR%d=", argument.rawValue()));
            }
        }

        // Live-Wert des Registers, falls zutreffend
        if (argument.type() == ArgumentType.REGISTER) {
            Object liveValue = organism.getDr(argument.rawValue());
            sb.append(formatDrValue(liveValue));
        }
        // Label-Namen anzeigen, falls vorhanden
        else if (argument.type() == ArgumentType.LABEL && metadata != null) {
            String labelName = metadata.labelAddressToName().get(argument.rawValue());
            if (labelName != null) {
                sb.append(labelName);
            } else {
                sb.append(argument.resolvedValue());
            }
        }
        // Ansonsten den aufgelösten Wert verwenden
        else {
            sb.append(argument.resolvedValue());
        }

        return sb.toString();
    }

    private String formatCoordinate(int[] coord) {
        if (coord == null) return "null";
        return "[" + Arrays.stream(coord)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining("|")) + "]";
    }

    public String formatDrValue(Object drVal) {
        if (drVal == null) {
            return "null";
        }

        if (drVal instanceof int[] vec) {
            return "V:" + formatCoordinate(vec);
        }

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

    private String formatGenericAction(Organism organism, Instruction action) {
        StringBuilder sb = new StringBuilder();
        int[] ipAtActionStart = Arrays.copyOf(organism.getIpBeforeFetch(), organism.getIpBeforeFetch().length);
        Symbol opcodeSymbol = world.getSymbol(ipAtActionStart);

        int opcodeFullId = opcodeSymbol.toInt();
        String opcodeName = Instruction.getInstructionNameById(opcodeFullId);
        int opcodeLength = Instruction.getInstructionLengthById(opcodeFullId);

        if (opcodeName != null && !opcodeName.startsWith("UNKNOWN")) {
            sb.append(opcodeName);
            if (opcodeLength > 1) {
                sb.append("(");
                int[] effectiveDv = organism.getDvBeforeFetch();
                int[] tempIpForArgs = Arrays.copyOf(ipAtActionStart, ipAtActionStart.length);

                for (int i = 0; i < opcodeLength - 1; i++) {
                    tempIpForArgs = organism.getNextInstructionPosition(tempIpForArgs, world, effectiveDv);
                    if (i > 0) sb.append(", ");
                    Symbol argSymbol = world.getSymbol(tempIpForArgs);
                    sb.append(argSymbol.value());
                }
                sb.append(")");
            }
        } else {
            sb.append("UNKNOWN_OP(").append(opcodeSymbol.value()).append(")");
        }
        return sb.toString();
    }
}
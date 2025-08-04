// src/main/java/org/evochora/Logger.java
package org.evochora;

import org.evochora.assembler.ArgumentType;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.assembler.AssemblyProgram;
import org.evochora.assembler.DisassembledInstruction;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class Logger {

    private final World world;
    private final Simulation simulation;

    public Logger(World world, Simulation simulation) {
        this.world = world;
        this.simulation = simulation;
    }

    /**
     * Holt die Info für den Footer (Zukunft).
     */
    public String getNextInstructionInfo(Organism organism) {
        if (organism.isDead()) {
            return "DEAD";
        }

        DisassembledInstruction disassembledInstruction = AssemblyProgram.getDisassembledInstructionDetailsForNextTick(organism);

        StringBuilder info = new StringBuilder();
        if (disassembledInstruction != null && disassembledInstruction.opcodeName() != null && !disassembledInstruction.opcodeName().equals("N/A")) {
            info.append(disassembledInstruction.opcodeName());
            if (!disassembledInstruction.arguments().isEmpty()) {
                info.append("(");
                for (int i = 0; i < disassembledInstruction.arguments().size(); i++) {
                    if (i > 0) info.append(", ");
                    info.append(disassembledInstruction.arguments().get(i).resolvedValue());
                }
                info.append(")");
            }
            if (disassembledInstruction.resolvedTargetCoordinate() != null && !disassembledInstruction.resolvedTargetCoordinate().isEmpty()) {
                info.append(" -> ").append(disassembledInstruction.resolvedTargetCoordinate());
            }
        } else {
            // Fallback, wenn Disassemblierung fehlschlägt oder keine Metadaten vorhanden sind
            int[] currentIp = Arrays.copyOf(organism.getIp(), organism.getIp().length);
            Symbol currentSymbol = world.getSymbol(currentIp);
            int opcodeFullId = currentSymbol.toInt();
            String opcodeName = Instruction.getInstructionNameById(opcodeFullId);
            int opcodeLength = Instruction.getInstructionLengthById(opcodeFullId);

            if (opcodeName != null && !opcodeName.startsWith("UNKNOWN")) {
                info.append(opcodeName);
                if (opcodeLength > 1) {
                    info.append("(");
                    int[] effectiveDv = organism.getDv();
                    int[] tempIpForArgs = Arrays.copyOf(currentIp, currentIp.length);

                    for (int i = 0; i < opcodeLength - 1; i++) {
                        tempIpForArgs = organism.getNextInstructionPosition(tempIpForArgs, world, effectiveDv);
                        if (i > 0) info.append(", ");
                        Symbol argSymbol = world.getSymbol(tempIpForArgs);
                        info.append(argSymbol.value());
                    }
                    info.append(")");
                }
            } else {
                info.append("UNKNOWN_OP (").append(currentSymbol.value()).append(")");
            }
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

        DisassembledInstruction disassembledInstruction = AssemblyProgram.getDisassembledInstructionDetailsForLastTick(organism);
        logLine.append(" | Planned: ");
        if (disassembledInstruction != null && disassembledInstruction.opcodeName() != null && !disassembledInstruction.opcodeName().equals("N/A")) {
            logLine.append(disassembledInstruction.opcodeName());

            if (!disassembledInstruction.arguments().isEmpty()) {
                logLine.append("(");
                for (int i = 0; i < disassembledInstruction.arguments().size(); i++) {
                    if (i > 0) logLine.append(", ");
                    var argument = disassembledInstruction.arguments().get(i);

                    if (argument.type() == ArgumentType.REGISTER) {
                        String regName = argument.resolvedValue();
                        int regIndex = argument.rawValue();
                        Object liveValue = organism.getDr(regIndex);
                        String formattedValue = formatDrValue(liveValue);
                        logLine.append(regName).append("=").append(formattedValue);
                    } else {
                        logLine.append(argument.resolvedValue());
                    }
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

    private String formatCoordinate(int[] coord) {
        if (coord == null) return "null";
        return "[" + Arrays.stream(coord)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining("|")) + "]";
    }

    private String formatDrValue(Object drVal) {
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
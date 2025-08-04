// src/main/java/org/evochora/Logger.java
package org.evochora;

import org.evochora.organism.Action;
import org.evochora.organism.IWorldModifyingAction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class Logger {

    private final World world;
    private final Simulation simulation;

    public Logger(World world, Simulation simulation) {
        this.world = world;
        this.simulation = simulation;
    }

    /**
     * Erzeugt eine String-Repräsentation des nächsten geplanten Befehls und seiner Argumente.
     * Wird in der Fußzeile verwendet.
     * @param organism Der Organismus, dessen nächste Instruktion ermittelt werden soll.
     * @return Ein String, der den nächsten Befehl und seine Argumente beschreibt.
     */
    public String getNextInstructionInfo(Organism organism) {
        if (organism.isDead()) {
            return "DEAD";
        }

        int[] currentIp = Arrays.copyOf(organism.getIpBeforeFetch(), organism.getIpBeforeFetch().length);
        Symbol currentSymbol = world.getSymbol(currentIp);

        int opcodeValue = currentSymbol.value();
        Config.Opcode opcodeDef = Config.OPCODE_DEFINITIONS.get(Config.TYPE_CODE | opcodeValue);

        StringBuilder info = new StringBuilder();
        if (opcodeDef != null) {
            info.append(opcodeDef.name());

            if (opcodeDef.length() > 1) { // Wenn es Argumente gibt
                info.append("(");

                int[] effectiveDv = organism.getDvBeforeFetch();

                int[] tempIpForArgs = Arrays.copyOf(currentIp, currentIp.length);
                // KORRIGIERT: Zuweisung des Rückgabewerts für den ersten Schritt
                tempIpForArgs = organism.getNextInstructionPosition(tempIpForArgs, world, effectiveDv);

                for (int i = 0; i < opcodeDef.length() - 1; i++) {
                    if (i > 0) info.append(", ");

                    Symbol argSymbol = world.getSymbol(tempIpForArgs);
                    info.append(argSymbol.value());

                    // KORRIGIERT: Zuweisung des Rückgabewerts für die weiteren Schritte
                    tempIpForArgs = organism.getNextInstructionPosition(tempIpForArgs, world, effectiveDv);
                }
                info.append(")");
            }
        } else {
            info.append("UNKNOWN_OP (").append(currentSymbol.value()).append(")");
        }
        return info.toString();
    }

    /**
     * Loggt die detaillierten Statusinformationen eines Organismus für den aktuellen Tick.
     * @param organism Der Organismus, dessen Tick-Details geloggt werden sollen.
     * @param plannedAction Die ursprünglich geplante Aktion des Organismus.
     */
    public void logTickDetails(Organism organism, Action plannedAction) {
        if (!organism.isLoggingEnabled()) {
            return;
        }

        StringBuilder logLine = new StringBuilder();

        logLine.append(String.format("Tick %-5d | ORG ID %-3d | ", simulation.getCurrentTick(), organism.getId()));

        logLine.append("ER: ").append(String.format("%-5d", organism.getEr()));
        logLine.append(" | IP: ").append(formatCoordinate(organism.getIpBeforeFetch()));
        logLine.append(" | DP: ").append(formatCoordinate(organism.getDp()));
        logLine.append(" | DV: ").append(formatCoordinate(organism.getDvBeforeFetch()));
        logLine.append(" | DRs: [");
        List<Object> drs = organism.getDrs();
        for (int i = 0; i < drs.size(); i++) {
            Object drVal = drs.get(i);
            String valStr = (drVal instanceof int[]) ? Arrays.toString((int[]) drVal) : (drVal != null ? drVal.toString() : "null");
            logLine.append(String.format("%d:%s", i, valStr));
            if (i < drs.size() - 1) logLine.append(", ");
        }
        logLine.append("]");

        logLine.append(" | Planned: ").append(formatActionAndArgs(organism, plannedAction));

        if (plannedAction.isExecutedInTick()) {
            logLine.append(" | Executed: YES");
            if (organism.isInstructionFailed()) {
                logLine.append(" | Failed: YES (").append(organism.getFailureReason()).append(")");
            } else {
                logLine.append(" | Failed: NO");
            }
        } else {
            logLine.append(" | Executed: NO");
            if (plannedAction.getConflictStatus() != Action.ConflictResolutionStatus.NOT_APPLICABLE) {
                logLine.append(" | Conflict: YES (Reason: ").append(plannedAction.getConflictStatus()).append(")");
            } else {
                logLine.append(" | Reason: Unknown (not executed, no conflict status)");
            }
        }

        System.out.println(logLine.toString());
    }

    private String formatCoordinate(int[] coord) {
        if (coord == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < coord.length; i++) {
            sb.append(coord[i]);
            if (i < coord.length - 1) sb.append("|");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Formatiert eine Aktion und ihre Argumente für das Logging.
     * Diese Methode versucht, die Argumente direkt aus der Welt zu lesen,
     * um den tatsächlichen Maschinencode zu reflektieren.
     * @param organism Der Organismus, der die Aktion ausführt.
     * @param action Die geplante Aktion.
     * @return Formatierter String der Aktion und ihrer Argumente.
     */
    private String formatActionAndArgs(Organism organism, Action action) {
        StringBuilder sb = new StringBuilder();
        int[] ipAtActionStart = Arrays.copyOf(organism.getIpBeforeFetch(), organism.getIpBeforeFetch().length);
        Symbol opcodeSymbol = world.getSymbol(ipAtActionStart);
        int opcodeValue = opcodeSymbol.value();
        Config.Opcode opcodeDef = Config.OPCODE_DEFINITIONS.get(Config.TYPE_CODE | opcodeValue);

        if (opcodeDef != null) {
            sb.append(opcodeDef.name());
            if (opcodeDef.length() > 1) { // Wenn es Argumente gibt
                sb.append("(");

                int[] effectiveDv = organism.getDvBeforeFetch();

                // KORRIGIERT: Zuweisung des Rückgabewerts für den ersten Schritt
                ipAtActionStart = organism.getNextInstructionPosition(ipAtActionStart, world, effectiveDv);

                for (int i = 0; i < opcodeDef.length() - 1; i++) {
                    if (i > 0) sb.append(", ");
                    Symbol argSymbol = world.getSymbol(ipAtActionStart);
                    sb.append(argSymbol.value());
                    // KORRIGIERT: Zuweisung des Rückgabewerts für die weiteren Schritte
                    ipAtActionStart = organism.getNextInstructionPosition(ipAtActionStart, world, effectiveDv);
                }
                sb.append(")");
            }
        } else {
            sb.append("UNKNOWN_OP(").append(opcodeSymbol.value()).append(")");
        }
        return sb.toString();
    }
}
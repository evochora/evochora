// src/main/java/org/evochora/organism/IfAction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.organism.Action;
import org.evochora.organism.Organism;
import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class IfAction extends Action {
    private final int reg1;
    private final int reg2;
    private final int type; // Typ des IF-Befehls (IF, IFLT, IFGT)

    public IfAction(Organism o, int r1, int r2, int t) { super(o); this.reg1 = r1; this.reg2 = r2; this.type = t; }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("IF-Befehle erwarten 2 Argumente: %REG1 %REG2");
        return List.of(registerMap.get(args[0].toUpperCase()), registerMap.get(args[1].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        int opType = world.getSymbol(organism.getIp()).toInt(); // Holt den vollständigen Opcode-Wert
        int[] tempIp = Arrays.copyOf(organism.getIp(), organism.getIp().length); // Kopie, um das IP nicht zu verändern
        return new IfAction(organism, organism.fetchArgument(tempIp, world), organism.fetchArgument(tempIp, world), opType);
    }

    @Override
    public void execute(Simulation simulation) {
        Object val1 = organism.getDr(reg1);
        Object val2 = organism.getDr(reg2);
        if (val1 instanceof Integer v1 && val2 instanceof Integer v2) {
            boolean conditionMet = false;
            // KORRIGIERT: Vergleich basierend auf dem reinen Opcode-Wert des Opcodes selbst.
            // Der 'type' Parameter dieser Klasse ist bereits der volle 32-Bit-Wert des Opcodes.
            // Wir müssen seinen reinen Opcode-Wert extrahieren (ohne Typ-Bits), um ihn mit den OP_-Konstanten zu vergleichen.
            int currentOpcodeValue = (type == 0) ? 0 : org.evochora.world.Symbol.fromInt(type).value();

            // KORRIGIERT: Zugriff auf den reinen Opcode-Wert der Vergleichs-Konstanten
            Config.Opcode opIfDef = Config.OPCODE_DEFINITIONS.get(Config.OP_IF);
            Config.Opcode opIfLtDef = Config.OPCODE_DEFINITIONS.get(Config.OP_IFLT);
            Config.Opcode opIfGtDef = Config.OPCODE_DEFINITIONS.get(Config.OP_IFGT);

            if (opIfDef != null && currentOpcodeValue == (opIfDef.id() & Config.VALUE_MASK) && v1.equals(v2)) conditionMet = true;
            if (opIfLtDef != null && currentOpcodeValue == (opIfLtDef.id() & Config.VALUE_MASK) && v1 < v2) conditionMet = true;
            if (opIfGtDef != null && currentOpcodeValue == (opIfGtDef.id() & Config.VALUE_MASK) && v1 > v2) conditionMet = true;

            if (conditionMet) {
                organism.setSkipIpAdvance(true); // Springt über den nächsten Befehl hinweg
            }
        } else {
            organism.instructionFailed("IF: Invalid DR types. Reg " + reg1 + " (" + (val1 != null ? val1.getClass().getSimpleName() : "null") + ") and Reg " + reg2 + " (" + (val2 != null ? val2.getClass().getSimpleName() : "null") + ") must be Integer.");
        }
    }
}
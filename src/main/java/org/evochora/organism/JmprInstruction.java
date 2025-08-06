// src/main/java/org/evochora/organism/JmprInstruction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import org.evochora.Config;
import org.evochora.world.Symbol;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.assembler.ArgumentType;

import java.util.List;
import java.util.Map;

/**
 * Die JMPR-Instruktion (Jump Register).
 * Springt zu einer neuen, programm-absoluten Position, die durch einen Vektor in einem Register definiert ist.
 * Syntax: JMPR %REG_TARGET
 */
public class JmprInstruction extends Instruction {
    public static final int ID = 10;

    private final int reg;

    public JmprInstruction(Organism o, int r) {
        super(o);
        this.reg = r;
    }

    static {
        Instruction.registerInstruction(JmprInstruction.class, ID, "JMPR", 2, JmprInstruction::plan, JmprInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER));
    }

    @Override
    public String getName() {
        return "JMPR";
    }

    @Override
    public int getLength() {
        return 2;
    }

    @Override
    protected int getFixedBaseCost() {
        return 1;
    }

    @Override
    public int getCost(Organism organism, World world, List<Integer> rawArguments) {
        return getFixedBaseCost();
    }

    @Override
    public ArgumentType getArgumentType(int argIndex) {
        if (argIndex == 0) {
            return ArgumentType.REGISTER;
        }
        throw new IllegalArgumentException("Ungültiger Argumentindex für JMPR: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int r = result.value();
        return new JmprInstruction(organism, r);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) {
            throw new IllegalArgumentException("JMPR erwartet 1 Argument: %REG_TARGET");
        }
        String arg = args[0].toUpperCase();

        Integer regId = registerMap.get(arg);
        if (regId == null) {
            throw new IllegalArgumentException("Ungültiges Register-Argument: " + arg);
        }

        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        Object targetObj = organism.getDr(reg);

        if (!(targetObj instanceof int[] programCoord)) {
            organism.instructionFailed("JMPR: Ungültiger Registertyp für Ziel (Reg " + reg + "). Erwartet Vektor (int[]), gefunden: " + (targetObj != null ? targetObj.getClass().getSimpleName() : "null") + ".");
            return;
        }

        if (programCoord.length != simulation.getWorld().getShape().length) {
            organism.instructionFailed("JMPR: Dimension des Ziel-Vektors stimmt nicht mit Welt-Dimension überein. Erwartet: " + simulation.getWorld().getShape().length + ", gefunden: " + programCoord.length + ".");
            return;
        }

        // KORREKTUR: Die programm-relative Koordinate aus dem Register wird in eine
        // absolute Welt-Koordinate umgerechnet, indem die Startposition des Organismus addiert wird.
        int[] initialPosition = organism.getInitialPosition();
        int[] worldCoord = new int[programCoord.length];
        for (int i = 0; i < programCoord.length; i++) {
            worldCoord[i] = initialPosition[i] + programCoord[i];
        }

        organism.setIp(worldCoord);
        organism.setSkipIpAdvance(true);
    }
}
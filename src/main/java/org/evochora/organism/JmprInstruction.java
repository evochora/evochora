// src/main/java/org/evochora/organism/JmprInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.ArgumentType;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.world.World;
import org.evochora.world.Symbol;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * Die JMPR-Instruktion (Jump Relative).
 * Springt um einen relativen Delta-Vektor, der direkt im Maschinencode steht.
 * Diese Instruktion ist für den Programmierer nicht direkt sichtbar; sie wird
 * vom Assembler generiert, wenn dieser einen `JUMP LABEL`-Befehl auflöst.
 */
public class JmprInstruction extends Instruction {
    // NEU: Eigene ID für JMPR
    public static final int ID = 20;

    private final int[] delta;

    public JmprInstruction(Organism o, int[] delta) {
        super(o);
        this.delta = delta;
    }

    static {
        Instruction.registerInstruction(JmprInstruction.class, ID, "JMPR", 1 + Config.WORLD_DIMENSIONS, JmprInstruction::plan, JmprInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.COORDINATE, 1, ArgumentType.COORDINATE));
    }


    @Override
    public String getName() {
        return "JMPR";
    }

    @Override
    public int getLength() {
        return 1 + Config.WORLD_DIMENSIONS;
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
        if (argIndex >= 0 && argIndex < Config.WORLD_DIMENSIONS) {
            return ArgumentType.COORDINATE;
        }
        throw new IllegalArgumentException("Ungültiger Argumentindex für JMPR: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        int[] delta = new int[Config.WORLD_DIMENSIONS];
        int[] currentPos = organism.getIp();

        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            Organism.FetchResult result = organism.fetchSignedArgument(currentPos, world);
            delta[i] = result.value();
            currentPos = result.nextIp();
        }

        return new JmprInstruction(organism, delta);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) {
            throw new IllegalArgumentException("JUMP erwartet 1 Argument (Label).");
        }
        String arg = args[0].toUpperCase();

        if (labelMap.containsKey(arg)) {
            return new AssemblerOutput.JumpInstructionRequest(arg);
        } else if (arg.contains("|")) {
            List<Integer> machineCode = new ArrayList<>();
            String[] vectorComponents = arg.split("\\|");
            if (vectorComponents.length != Config.WORLD_DIMENSIONS) {
                throw new IllegalArgumentException("Vektor hat falsche Dimension für JUMP: " + arg);
            }
            for (String component : vectorComponents) {
                try {
                    machineCode.add(new Symbol(Config.TYPE_DATA, Integer.parseInt(component.strip())).toInt());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Vektor-Komponente ist keine gültige Zahl: " + component);
                }
            }
            return new AssemblerOutput.CodeSequence(machineCode);
        } else {
            throw new IllegalArgumentException("JUMP kann nur Labels oder Vektor-Literale als Argumente akzeptieren: " + arg);
        }
    }

    @Override
    public void execute(Simulation simulation) {
        int[] targetIp = organism.getTargetCoordinate(organism.getIpBeforeFetch(), this.delta, simulation.getWorld());
        organism.setIp(targetIp);
        organism.setSkipIpAdvance(true);
    }
}
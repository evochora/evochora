// src/main/java/org/evochora/organism/JmpiInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.ArgumentType;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

/**
 * Die JMPI-Instruktion (Jump Immediate).
 * Springt um einen relativen Delta-Vektor, der direkt im Maschinencode steht (als Literal)
 * oder vom Assembler durch Auflösen eines Labels berechnet wird.
 * Dieser Befehl ist für positionsunabhängigen Code unerlässlich.
 */
public class JmpiInstruction extends Instruction {
    public static final int ID = 20;

    private final int[] delta;

    public JmpiInstruction(Organism o, int[] delta) {
        super(o);
        this.delta = delta;
    }

    static {
        Instruction.registerInstruction(JmpiInstruction.class, ID, "JMPI", 1 + Config.WORLD_DIMENSIONS, JmpiInstruction::plan, JmpiInstruction::assemble);
        // Argumente sind Koordinaten-Komponenten
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.COORDINATE, 1, ArgumentType.COORDINATE));
    }


    @Override
    public String getName() {
        return "JMPI";
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
        throw new IllegalArgumentException("Ungültiger Argumentindex für JMPI: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        int[] delta = new int[Config.WORLD_DIMENSIONS];
        int[] currentPos = organism.getIp();

        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            Organism.FetchResult result = organism.fetchSignedArgument(currentPos, world);
            delta[i] = result.value();
            currentPos = result.nextIp();
        }

        return new JmpiInstruction(organism, delta);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) {
            throw new IllegalArgumentException("JMPI erwartet 1 Argument (Label oder Vektor-Literal).");
        }
        String arg = args[0].toUpperCase();

        // Wenn es ein bekanntes Label ist, wird eine Sprung-Anfrage an den Assembler gestellt.
        if (labelMap.containsKey(arg)) {
            return new AssemblerOutput.JumpInstructionRequest(arg);
        }
        // Wenn es ein Vektor-Literal ist (z.B. 1|0), wird es direkt verarbeitet.
        else if (arg.contains("|")) {
            List<Integer> machineCode = new ArrayList<>();
            String[] vectorComponents = arg.split("\\|");
            if (vectorComponents.length != Config.WORLD_DIMENSIONS) {
                throw new IllegalArgumentException("Vektor-Literal hat falsche Dimension für JMPI: " + arg);
            }
            for (String component : vectorComponents) {
                try {
                    machineCode.add(new Symbol(Config.TYPE_DATA, Integer.parseInt(component.strip())).toInt());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Vektor-Komponente ist keine gültige Zahl: " + component);
                }
            }
            return new AssemblerOutput.CodeSequence(machineCode);
        }
        // Fehler, wenn ein Registername verwendet wird. Dafür ist JMPR zuständig.
        else if (registerMap.containsKey(arg)) {
            throw new IllegalArgumentException(String.format("JMPI kann keine Register als Argumente akzeptieren. Verwenden Sie JMPR für Sprünge mit Registern. Argument war: %s", arg));
        }
        // Allgemeiner Fehler für unbekannte Argumente.
        else {
            throw new IllegalArgumentException(String.format("Argument für JMPI ist weder ein bekanntes Label, noch ein gültiges Vektor-Literal: '%s'", arg));
        }
    }

    @Override
    public void execute(Simulation simulation) {
        // Berechnet die Zielkoordinate durch Addition des relativen Deltas zum IP *vor* dem Befehl.
        int[] targetIp = organism.getTargetCoordinate(organism.getIpBeforeFetch(), this.delta, simulation.getWorld());
        organism.setIp(targetIp);
        organism.setSkipIpAdvance(true);
    }
}
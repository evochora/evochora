// src/main/java/org/evochora/organism/JmprInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.ArgumentType;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.world.World;

import java.util.List;
import java.util.Map;

/**
 * Die JMPR-Instruktion (Jump Relative).
 * Springt um einen relativen Delta-Vektor, der direkt im Maschinencode steht.
 * Diese Instruktion ist für den Programmierer nicht direkt sichtbar; sie wird
 * vom Assembler generiert, wenn dieser einen `JMP LABEL`-Befehl auflöst.
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

        // Lese alle N Komponenten des Delta-Vektors nacheinander
        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            // Wir verwenden fetchSignedArgument, um auch negative Sprünge (z.B. bei Schleifen) zu ermöglichen
            Organism.FetchResult result = organism.fetchSignedArgument(currentPos, world);
            delta[i] = result.value();
            currentPos = result.nextIp(); // Die Position für den nächsten Lese-Schritt aktualisieren
        }

        return new JmprInstruction(organism, delta);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        // Diese Methode sollte nie aufgerufen werden.
        throw new UnsupportedOperationException("JMPR kann nicht direkt im Code verwendet werden; es wird vom Assembler für 'JMP LABEL' generiert.");
    }

    @Override
    public void execute(Simulation simulation) {
        int[] targetIp = organism.getTargetCoordinate(organism.getIpBeforeFetch(), this.delta, simulation.getWorld());
        organism.setIp(targetIp);
        organism.setSkipIpAdvance(true);
    }
}
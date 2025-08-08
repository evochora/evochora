package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JmpiInstruction extends Instruction {

    public static final int LENGTH = 1 + Config.WORLD_DIMENSIONS;

    private final int[] delta;

    public JmpiInstruction(Organism o, int[] delta, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.delta = delta;
    }

    @Override
    public void execute(Simulation simulation) {
        int[] targetIp = organism.getTargetCoordinate(organism.getIpBeforeFetch(), this.delta, simulation.getWorld());
        organism.setIp(targetIp);
        organism.setSkipIpAdvance(true);
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        int[] delta = new int[Config.WORLD_DIMENSIONS];
        int[] currentPos = organism.getIp();
        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            Organism.FetchResult result = organism.fetchSignedArgument(currentPos, world);
            delta[i] = result.value();
            currentPos = result.nextIp();
        }
        return new JmpiInstruction(organism, delta, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("JMPI erwartet 1 Argument (Label oder Vektor-Literal).");
        String arg = args[0].toUpperCase();
        if (arg.contains("|")) {
            List<Integer> machineCode = new ArrayList<>();
            String[] vectorComponents = arg.split("\\|");
            if (vectorComponents.length != Config.WORLD_DIMENSIONS) throw new IllegalArgumentException("Vektor-Literal hat falsche Dimension für JMPI: " + arg);
            for (String component : vectorComponents) {
                try {
                    machineCode.add(new Symbol(Config.TYPE_DATA, Integer.parseInt(component.strip())).toInt());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Vektor-Komponente ist keine gültige Zahl: " + component);
                }
            }
            return new AssemblerOutput.CodeSequence(machineCode);
        } else if (registerMap.containsKey(arg)) {
            throw new IllegalArgumentException("JMPI kann keine Register als Argumente akzeptieren. Verwenden Sie JMPR.");
        } else {
            return new AssemblerOutput.JumpInstructionRequest(arg);
        }
    }
}
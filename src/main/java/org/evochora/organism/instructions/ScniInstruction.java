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

public class ScniInstruction extends Instruction {

    // LENGTH: opcode + targetReg + WORLD_DIM vector components
    public static final int LENGTH = 2 + Config.WORLD_DIMENSIONS;

    private final int targetReg;
    private final int[] vector;

    public ScniInstruction(Organism organism, int targetReg, int[] vector, int fullOpcodeId) {
        super(organism, fullOpcodeId);
        this.targetReg = targetReg;
        this.vector = vector;
    }

    @Override
    public void execute(Simulation simulation) {
        int[] target = organism.getTargetCoordinate(organism.getDp(), vector, simulation.getWorld());
        Symbol scanned = simulation.getWorld().getSymbol(target); // non-destructive
        if (!organism.setDr(targetReg, scanned.toInt())) {
            organism.instructionFailed("SCNI: Failed to set value to DR " + targetReg + ".");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();

        // fetch target register then WORLD_DIMENSIONS signed vector components
        Organism.FetchResult resTr = organism.fetchArgument(organism.getIp(), world);
        int tr = resTr.value();
        int[] vec = new int[Config.WORLD_DIMENSIONS];
        int[] curr = resTr.nextIp();
        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            Organism.FetchResult comp = organism.fetchSignedArgument(curr, world);
            vec[i] = comp.value();
            curr = comp.nextIp();
        }
        return new ScniInstruction(organism, tr, vec, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SCNI erwartet genau 2 Argumente: %REG_TARGET VEC");
        Integer targetRegId = registerMap.get(args[0].toUpperCase());
        if (targetRegId == null) throw new IllegalArgumentException("Ungültiges Register-Argument: " + args[0]);
        String[] vecParts = args[1].split("\\|");
        if (vecParts.length != Config.WORLD_DIMENSIONS) {
            throw new IllegalArgumentException("SCNI: falsche Vektor-Dimension: " + args[1]);
        }
        List<Integer> code = new ArrayList<>();
        code.add(new Symbol(Config.TYPE_DATA, targetRegId).toInt());
        for (String p : vecParts) {
            int val = Integer.parseInt(p.strip());
            code.add(new Symbol(Config.TYPE_DATA, val).toInt());
        }
        return new AssemblerOutput.CodeSequence(code);
    }
}

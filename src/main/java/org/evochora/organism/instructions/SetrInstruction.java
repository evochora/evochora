package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.List;
import java.util.Map;

public class SetrInstruction extends Instruction {

    public static final int LENGTH = 3;

    private final int destReg;
    private final int srcReg;

    public SetrInstruction(Organism o, int d, int s, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.destReg = d;
        this.srcReg = s;
    }

    @Override
    public void execute(Simulation simulation) {
        Object value = organism.getDr(srcReg);
        if (value == null) {
            return;
        }
        if (!organism.setDr(destReg, value)) {
            organism.instructionFailed("SETR: Failed to set value to DR " + destReg + ".");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int dest = result1.value();
        Organism.FetchResult result2 = organism.fetchArgument(result1.nextIp(), world);
        int src = result2.value();
        return new SetrInstruction(organism, dest, src, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SETR erwartet 2 Argumente: %REG_DEST %REG_SRC");
        Integer destRegId = registerMap.get(args[0].toUpperCase());
        if (destRegId == null) throw new IllegalArgumentException("Ungültiges Register-Argument für Ziel: " + args[0]);
        Integer srcRegId = registerMap.get(args[1].toUpperCase());
        if (srcRegId == null) throw new IllegalArgumentException("Ungültiges Register-Argument für Quelle: " + args[1]);
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, destRegId).toInt(),
                new Symbol(Config.TYPE_DATA, srcRegId).toInt()
        ));
    }
}
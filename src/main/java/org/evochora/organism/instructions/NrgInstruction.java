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

public class NrgInstruction extends Instruction {

    public static final int LENGTH = 2;

    private final int targetReg;

    public NrgInstruction(Organism o, int tr, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.targetReg = tr;
    }

    @Override
    public void execute(Simulation simulation) {
        if (!organism.setDr(targetReg, new Symbol(Config.TYPE_DATA, organism.getEr()).toInt())) {
            organism.instructionFailed("NRG: Failed to set energy to DR " + targetReg + ".");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int tr = result.value();
        return new NrgInstruction(organism, tr, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("NRG erwartet genau 1 Argument: %REG_TARGET");
        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) throw new IllegalArgumentException("Ungültiges Register-Argument: " + args[0]);
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }
}
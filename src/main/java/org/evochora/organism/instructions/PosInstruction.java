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

public class PosInstruction extends Instruction {

    public static final int LENGTH = 2;

    private final int reg;

    public PosInstruction(Organism o, int r, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.reg = r;
    }

    @Override
    public void execute(Simulation simulation) {
        int[] currentIp = organism.getIp();
        int[] initialPosition = organism.getInitialPosition();
        if (currentIp.length != initialPosition.length) {
            organism.instructionFailed("POS: Dimensionen von IP und Erzeugungsposition stimmen nicht überein.");
            return;
        }
        int[] delta = new int[currentIp.length];
        for (int i = 0; i < currentIp.length; i++) {
            delta[i] = currentIp[i] - initialPosition[i];
        }
        if (!organism.setDr(reg, delta)) {
            organism.instructionFailed("POS: Failed to set result to DR " + reg + ".");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int r = result.value();
        return new PosInstruction(organism, r, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("POS erwartet genau 1 Argument: %REG_TARGET");
        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) throw new IllegalArgumentException("Ungültiges Register-Argument für POS: " + args[0]);
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }
}
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

public class TurnInstruction extends Instruction {

    public static final int LENGTH = 2;

    private final int reg;

    public TurnInstruction(Organism o, int r, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.reg = r;
    }

    @Override
    public void execute(Simulation simulation) {
        Object newDvObj = organism.getDr(reg);
        if (!(newDvObj instanceof int[] v)) {
            organism.instructionFailed("TURN: Ungültiger Registertyp für neuen DV (Reg " + reg + "). Erwartet Vektor (int[]).");
            return;
        }
        if (!organism.isUnitVector(v)) {
            return;
        }
        if (v.length != organism.getDv().length) {
            organism.instructionFailed("TURN: Dimension des neuen DV-Vektors stimmt nicht überein.");
            return;
        }
        organism.setDv(v);
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int r = result.value();
        return new TurnInstruction(organism, r, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("TURN erwartet genau 1 Argument: %REG_VEC");
        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) throw new IllegalArgumentException("Ungültiges Register-Argument: " + args[0]);
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }
}
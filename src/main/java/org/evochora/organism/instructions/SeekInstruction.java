package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SeekInstruction extends Instruction {

    public static final int LENGTH = 2;

    private final int reg;

    public SeekInstruction(Organism o, int r, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.reg = r;
    }

    @Override
    public void execute(Simulation simulation) {
        Object vecObj = organism.getDr(reg);
        if (!(vecObj instanceof int[] v)) {
            organism.instructionFailed("SEEK: Ungültiger Registertyp für Vektor (Reg " + reg + ").");
            return;
        }
        if (!organism.isUnitVector(v)) {
            return;
        }
        int[] targetDp = organism.getTargetCoordinate(organism.getDp(), v, simulation.getWorld());
        if (simulation.getWorld().getSymbol(targetDp).isEmpty()) {
            organism.setDp(targetDp);
        } else {
            organism.instructionFailed("SEEK: Ziel-DP-Zelle ist nicht leer bei " + Arrays.toString(targetDp) + ".");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int r = result.value();
        return new SeekInstruction(organism, r, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("SEEK erwartet genau 1 Argument: %REG_VEC");
        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) throw new IllegalArgumentException("Ungültiges Register-Argument: " + args[0]);
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }
}
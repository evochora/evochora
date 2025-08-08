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

public class ScanInstruction extends Instruction {

    public static final int LENGTH = 3;

    private final int targetReg;
    private final int vecReg;

    public ScanInstruction(Organism o, int tr, int vr, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.targetReg = tr;
        this.vecReg = vr;
    }

    @Override
    public void execute(Simulation simulation) {
        Object vecObj = organism.getDr(vecReg);
        if (!(vecObj instanceof int[] v)) {
            organism.instructionFailed("SCAN: Ungültiger Registertyp für Vektor (Reg " + vecReg + ").");
            return;
        }
        if (!organism.isUnitVector(v)) {
            return;
        }
        int[] target = organism.getTargetCoordinate(organism.getDp(), v, simulation.getWorld());
        Symbol scannedSymbol = simulation.getWorld().getSymbol(target);
        if (!organism.setDr(targetReg, scannedSymbol.toInt())) {
            organism.instructionFailed("SCAN: Failed to set value to DR " + targetReg + ".");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int tr = result1.value();
        Organism.FetchResult result2 = organism.fetchArgument(result1.nextIp(), world);
        int vr = result2.value();
        return new ScanInstruction(organism, tr, vr, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SCAN erwartet genau 2 Argumente: %REG_TARGET %REG_VEC");
        Integer targetRegId = registerMap.get(args[0].toUpperCase());
        Integer vecRegId = registerMap.get(args[1].toUpperCase());
        if (targetRegId == null || vecRegId == null) throw new IllegalArgumentException(String.format("Ungültiges Register-Argument. Target: '%s', Vec: '%s'", args[0], args[1]));
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, targetRegId).toInt(),
                new Symbol(Config.TYPE_DATA, vecRegId).toInt()
        ));
    }
}
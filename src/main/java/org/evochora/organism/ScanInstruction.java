// src/main/java/org/evochora/organism/ScanInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.World;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.assembler.ArgumentType;
import org.evochora.world.Symbol;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class ScanInstruction extends Instruction {
    public static final int ID = 16;

    private final int targetReg, vecReg;

    public ScanInstruction(Organism o, int tr, int vr) {
        super(o);
        this.targetReg = tr;
        this.vecReg = vr;
    }

    static {
        Instruction.registerInstruction(ScanInstruction.class, ID, "SCAN", 3, ScanInstruction::plan, ScanInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.REGISTER));
    }

    @Override
    public String getName() {
        return "SCAN";
    }

    @Override
    public int getLength() {
        return 3;
    }

    @Override
    protected int getFixedBaseCost() {
        return 0;
    }

    @Override
    public int getCost(Organism organism, World world, List<Integer> rawArguments) {
        return getFixedBaseCost();
    }

    @Override
    public ArgumentType getArgumentType(int argIndex) {
        return switch (argIndex) {
            case 0, 1 -> ArgumentType.REGISTER;
            default -> throw new IllegalArgumentException("Ungültiger Argumentindex für SCAN: " + argIndex);
        };
    }

    public static Instruction plan(Organism organism, World world) {
        // GEÄNDERT: Neue Logik mit FetchResult
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int tr = result1.value();

        Organism.FetchResult result2 = organism.fetchArgument(result1.nextIp(), world);
        int vr = result2.value();

        return new ScanInstruction(organism, tr, vr);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SCAN erwartet 2 Argumente: %REG_TARGET %REG_VEC");

        int targetRegId = registerMap.get(args[0].toUpperCase());
        int vecRegId = registerMap.get(args[1].toUpperCase());

        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, targetRegId).toInt(),
                new Symbol(Config.TYPE_DATA, vecRegId).toInt()
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        Object vecObj = organism.getDr(vecReg);

        if (!(vecObj instanceof int[] v)) {
            organism.instructionFailed("SCAN: Ungültiger Registertyp für Vektor (Reg " + vecReg + "). Erwartet Vektor (int[]), gefunden: " + (vecObj != null ? vecObj.getClass().getSimpleName() : "null") + ".");
            return;
        }

        if (!organism.isUnitVector(v)) {
            return;
        }

        int[] target = organism.getTargetCoordinate(organism.getDp(), v, simulation.getWorld());
        Symbol scannedSymbol = simulation.getWorld().getSymbol(target);

        // Der volle Wert des Symbols (inkl. Typ) wird in das Register geschrieben.
        if (!organism.setDr(targetReg, scannedSymbol.toInt())) {
            organism.instructionFailed("SCAN: Failed to set value to DR " + targetReg + ". Possible invalid register index.");
        }
    }
}
// src/main/java/org/evochora/organism/SeekInstruction.java
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

public class SeekInstruction extends Instruction {
    public static final int ID = 12;

    private final int reg;

    public SeekInstruction(Organism o, int r) {
        super(o);
        this.reg = r;
    }

    static {
        Instruction.registerInstruction(SeekInstruction.class, ID, "SEEK", 2, SeekInstruction::plan, SeekInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER));
    }

    @Override
    public String getName() {
        return "SEEK";
    }

    @Override
    public int getLength() {
        return 2;
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
        if (argIndex == 0) {
            return ArgumentType.REGISTER;
        }
        throw new IllegalArgumentException("Ungültiger Argumentindex für SEEK: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        // GEÄNDERT: Neue Logik mit FetchResult
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int r = result.value();
        return new SeekInstruction(organism, r);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("SEEK erwartet 1 Argument: %REG_VEC");

        int regId = registerMap.get(args[0].toUpperCase());

        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        Object vecObj = organism.getDr(reg);

        if (!(vecObj instanceof int[] v)) {
            organism.instructionFailed("SEEK: Ungültiger Registertyp für Vektor (Reg " + reg + "). Erwartet Vektor (int[]), gefunden: " + (vecObj != null ? vecObj.getClass().getSimpleName() : "null") + ".");
            return;
        }

        if (!organism.isUnitVector(v)) {
            return;
        }

        int[] targetDp = organism.getTargetCoordinate(organism.getDp(), v, simulation.getWorld());

        if (simulation.getWorld().getSymbol(targetDp).isEmpty()) {
            organism.setDp(targetDp);
        } else {
            organism.instructionFailed("SEEK: Ziel-DP-Zelle ist nicht leer bei " + Arrays.toString(targetDp) + ". Aktueller Inhalt: " + simulation.getWorld().getSymbol(targetDp).toInt() + ".");
        }
    }
}
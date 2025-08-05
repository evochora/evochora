// src/main/java/org/evochora/organism/TurnInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.World;
import org.evochora.world.Symbol;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.assembler.ArgumentType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TurnInstruction extends Instruction {
    public static final int ID = 11;

    private final int reg;

    public TurnInstruction(Organism o, int r) {
        super(o);
        this.reg = r;
    }

    static {
        Instruction.registerInstruction(TurnInstruction.class, ID, "TURN", 2, TurnInstruction::plan, TurnInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER));
    }

    @Override
    public String getName() {
        return "TURN";
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
        throw new IllegalArgumentException("Ungültiger Argumentindex für TURN: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        // GEÄNDERT: Neue Logik mit FetchResult
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int r = result.value();
        return new TurnInstruction(organism, r);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("TURN erwartet 1 Argument: %REG_VEC");

        int regId = registerMap.get(args[0].toUpperCase());

        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        Object newDvObj = organism.getDr(reg);

        if (!(newDvObj instanceof int[] v)) {
            organism.instructionFailed("TURN: Ungültiger Registertyp für neuen DV (Reg " + reg + "). Erwartet Vektor (int[]), gefunden: " + (newDvObj != null ? newDvObj.getClass().getSimpleName() : "null") + ".");
            return;
        }

        if (!organism.isUnitVector(v)) {
            return;
        }

        if (v.length != organism.getDv().length) {
            organism.instructionFailed("TURN: Dimension des neuen DV-Vektors stimmt nicht überein. Erwartet: " + organism.getDv().length + ", gefunden: " + v.length + ".");
            return;
        }

        organism.setDv(v);
    }
}
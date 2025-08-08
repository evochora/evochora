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
import java.util.Random;

public class RandInstruction extends Instruction {

    public static final int LENGTH = 2;
    private static final int ENERGY_COST = 2;

    private final int reg;

    public RandInstruction(Organism o, int r, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.reg = r;
    }

    @Override
    public int getCost(Organism organism, World world, List<Integer> rawArguments) {
        return ENERGY_COST;
    }

    @Override
    public void execute(Simulation simulation) {
        Object valObj = organism.getDr(reg);
        if (valObj instanceof int[]) {
            organism.instructionFailed("RAND: Vektor kann nicht als Obergrenze verwendet werden.");
            return;
        }
        if (!(valObj instanceof Integer valRaw)) {
            organism.instructionFailed("RAND: Ungültiger Registertyp. Register " + reg + " muss ein skalarer Integer sein.");
            return;
        }
        Symbol s = Symbol.fromInt(valRaw);
        int upperBound = s.toScalarValue();
        if (upperBound <= 0) {
            organism.instructionFailed("RAND: Obergrenze muss größer als 0 sein. Wert war: " + upperBound);
            organism.setDr(reg, new Symbol(s.type(), 0).toInt());
            return;
        }
        Random random = organism.getRandom();
        int randomValue = random.nextInt(upperBound);
        organism.setDr(reg, new Symbol(s.type(), randomValue).toInt());
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int r = result.value();
        return new RandInstruction(organism, r, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("RAND erwartet genau 1 Argument: %REG");
        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) throw new IllegalArgumentException("Ungültiges Register-Argument: " + args[0]);
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }
}
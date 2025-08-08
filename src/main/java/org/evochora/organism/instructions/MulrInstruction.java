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

public class MulrInstruction extends Instruction {

    public static final int LENGTH = 3;
    private static final int ENERGY_COST = 5;

    private final int reg1;
    private final int reg2;

    public MulrInstruction(Organism o, int r1, int r2, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.reg1 = r1;
        this.reg2 = r2;
    }

    @Override
    public int getCost(Organism organism, World world, List<Integer> rawArguments) {
        return ENERGY_COST;
    }

    @Override
    public void execute(Simulation simulation) {
        Object val1Obj = organism.getDr(reg1);
        Object val2Obj = organism.getDr(reg2);
        if (val1Obj instanceof int[] || val2Obj instanceof int[]) {
            organism.instructionFailed("MULR: Vektoren können nicht multipliziert werden.");
            return;
        }
        if (!(val1Obj instanceof Integer v1Raw) || !(val2Obj instanceof Integer v2Raw)) {
            organism.instructionFailed("MULR: Ungültige Registertypen. Beide Register müssen skalare Integer sein.");
            return;
        }
        Symbol s1 = Symbol.fromInt(v1Raw);
        Symbol s2 = Symbol.fromInt(v2Raw);
        if (Config.STRICT_TYPING && s1.type() != s2.type()) {
            organism.instructionFailed("MULR: Registertypen müssen im strikten Modus übereinstimmen.");
            return;
        }
        long resultValue = (long) s1.toScalarValue() * s2.toScalarValue();
        organism.setDr(reg1, new Symbol(s1.type(), (int)resultValue).toInt());
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int r1 = result1.value();
        Organism.FetchResult result2 = organism.fetchArgument(result1.nextIp(), world);
        int r2 = result2.value();
        return new MulrInstruction(organism, r1, r2, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("MULR erwartet 2 Argumente: %REG1 %REG2");
        Integer reg1Id = registerMap.get(args[0].toUpperCase());
        if (reg1Id == null) throw new IllegalArgumentException("Ungültiges Register-Argument: " + args[0]);
        Integer reg2Id = registerMap.get(args[1].toUpperCase());
        if (reg2Id == null) throw new IllegalArgumentException("Ungültiges Register-Argument: " + args[1]);
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, reg1Id).toInt(),
                new Symbol(Config.TYPE_DATA, reg2Id).toInt()
        ));
    }
}
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

public class NotInstruction extends Instruction {

    public static final int LENGTH = 2;

    private final int reg1;

    public NotInstruction(Organism o, int r1, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.reg1 = r1;
    }

    @Override
    public void execute(Simulation simulation) {
        Object val1Obj = organism.getDr(reg1);
        if (val1Obj instanceof int[]) {
            organism.instructionFailed("NOT: Vektoren sind nicht für bitweise Operationen erlaubt.");
            return;
        }
        if (!(val1Obj instanceof Integer v1Raw)) {
            organism.instructionFailed("NOT: Ungültiger Registertyp. Register " + reg1 + " muss ein skalarer Integer sein.");
            return;
        }
        Symbol s1 = Symbol.fromInt(v1Raw);
        int resultValue = ~s1.toScalarValue();
        organism.setDr(reg1, new Symbol(s1.type(), resultValue).toInt());
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int r1 = result1.value();
        return new NotInstruction(organism, r1, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("NOT erwartet genau 1 Argument: %REG");
        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) throw new IllegalArgumentException("Ungültiges Register-Argument: " + args[0]);
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }
}
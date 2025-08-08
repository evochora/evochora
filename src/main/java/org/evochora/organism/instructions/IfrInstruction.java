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

public class IfrInstruction extends Instruction {

    public static final int LENGTH = 3;
    public static final int ID_IFR = 7;
    public static final int ID_LTR = 8;
    public static final int ID_GTR = 9;

    private final int reg1;
    private final int reg2;

    public IfrInstruction(Organism o, int r1, int r2, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.reg1 = r1;
        this.reg2 = r2;
    }

    @Override
    public void execute(Simulation simulation) {
        Object val1Obj = organism.getDr(reg1);
        Object val2Obj = organism.getDr(reg2);
        boolean conditionMet = false;
        if (val1Obj instanceof int[] v1 && val2Obj instanceof int[] v2) {
            if ((fullOpcodeId & Config.VALUE_MASK) == ID_IFR) {
                conditionMet = Arrays.equals(v1, v2);
            }
        } else if (val1Obj instanceof Integer v1Raw && val2Obj instanceof Integer v2Raw) {
            Symbol s1 = Symbol.fromInt(v1Raw);
            Symbol s2 = Symbol.fromInt(v2Raw);
            if (Config.STRICT_TYPING) {
                if (s1.type() == s2.type()) {
                    conditionMet = performComparison(s1.toScalarValue(), s2.toScalarValue());
                }
            } else {
                conditionMet = performComparison(s1.toScalarValue(), s2.toScalarValue());
            }
        }
        if (!conditionMet) {
            organism.skipNextInstruction(organism.getSimulation().getWorld());
        }
    }

    private boolean performComparison(int v1, int v2) {
        int opcodeValue = this.fullOpcodeId & Config.VALUE_MASK;
        if (opcodeValue == ID_IFR) return v1 == v2;
        if (opcodeValue == ID_LTR) return v1 < v2;
        if (opcodeValue == ID_GTR) return v1 > v2;
        return false;
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int r1 = result1.value();
        Organism.FetchResult result2 = organism.fetchArgument(result1.nextIp(), world);
        int r2 = result2.value();
        return new IfrInstruction(organism, r1, r2, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("IFR/LTR/GTR erwarten genau 2 Register-Argumente.");
        Integer reg1Id = registerMap.get(args[0].toUpperCase());
        if (reg1Id == null) throw new IllegalArgumentException("Ungültiges Register-Argument für Reg1: " + args[0]);
        Integer reg2Id = registerMap.get(args[1].toUpperCase());
        if (reg2Id == null) throw new IllegalArgumentException("Ungültiges Register-Argument für Reg2: " + args[1]);
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, reg1Id).toInt(),
                new Symbol(Config.TYPE_DATA, reg2Id).toInt()
        ));
    }
}
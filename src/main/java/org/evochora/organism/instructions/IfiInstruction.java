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

public class IfiInstruction extends Instruction {

    public static final int LENGTH = 3;

    private final int reg1;
    private final int literalValue;

    public IfiInstruction(Organism o, int r1, int literal, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.reg1 = r1;
        this.literalValue = literal;
    }

    @Override
    public void execute(Simulation simulation) {
        Object val1Obj = organism.getDr(reg1);
        boolean conditionMet = false;

        if (val1Obj instanceof Integer v1Raw) {
            Symbol s1 = Symbol.fromInt(v1Raw);
            Symbol s2 = Symbol.fromInt(this.literalValue);

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
        if (opcodeValue == IfrInstruction.ID_IFR) return v1 == v2; // IFI uses the same ID logic
        if (opcodeValue == IfrInstruction.ID_LTR) return v1 < v2; // LTI uses the same ID logic
        if (opcodeValue == IfrInstruction.ID_GTR) return v1 > v2; // GTI uses the same ID logic
        return false;
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int r1 = result1.value();
        int[] secondArgIp = organism.getNextInstructionPosition(result1.nextIp(), world, organism.getDvBeforeFetch());
        int literal = world.getSymbol(secondArgIp).toInt();
        return new IfiInstruction(organism, r1, literal, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("IFI/LTI/GTI erwarten genau 2 Argumente: %REG TYPE:WERT");
        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) throw new IllegalArgumentException("Ungültiges Register-Argument: " + args[0]);
        String[] literalParts = args[1].split(":");
        if (literalParts.length != 2) throw new IllegalArgumentException("Literal muss das Format TYPE:WERT haben: " + args[1]);
        String typeName = literalParts[0].toUpperCase();
        int value = Integer.parseInt(literalParts[1]);
        int type = switch (typeName) {
            case "CODE" -> Config.TYPE_CODE;
            case "DATA" -> Config.TYPE_DATA;
            case "ENERGY" -> Config.TYPE_ENERGY;
            case "STRUCTURE" -> Config.TYPE_STRUCTURE;
            default -> throw new IllegalArgumentException("Unbekannter Typ für Literal: " + typeName);
        };
        int fullLiteralValue = new Symbol(type, value).toInt();
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt(),
                fullLiteralValue
        ));
    }
}
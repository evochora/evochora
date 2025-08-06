// src/main/java/org/evochora/organism/IfrInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.ArgumentType;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.List;
import java.util.Map;
import java.util.Arrays;

public class IfrInstruction extends Instruction {

    public static final int ID_IFR = 7;
    public static final int ID_LTR = 8;
    public static final int ID_GTR = 9;

    private final int reg1;
    private final int reg2;
    private final int fullOpcodeId;

    public IfrInstruction(Organism o, int r1, int r2, int fullOpcodeId) {
        super(o);
        this.reg1 = r1;
        this.reg2 = r2;
        this.fullOpcodeId = fullOpcodeId;
    }

    static {
        Instruction.registerInstruction(IfrInstruction.class, ID_IFR, "IFR", 3, IfrInstruction::plan, IfrInstruction::assemble);
        Instruction.registerArgumentTypes(ID_IFR, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.REGISTER));
        Instruction.registerInstruction(IfrInstruction.class, ID_LTR, "LTR", 3, IfrInstruction::plan, IfrInstruction::assemble);
        Instruction.registerArgumentTypes(ID_LTR, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.REGISTER));
        Instruction.registerInstruction(IfrInstruction.class, ID_GTR, "GTR", 3, IfrInstruction::plan, IfrInstruction::assemble);
        Instruction.registerArgumentTypes(ID_GTR, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.REGISTER));
    }


    @Override
    public String getName() {
        return Instruction.getInstructionNameById(fullOpcodeId);
    }

    @Override
    public int getLength() {
        return 3;
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
        if (argIndex == 0 || argIndex == 1) return ArgumentType.REGISTER;
        throw new IllegalArgumentException("Ungültiger Argumentindex für " + getName() + ": " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        int[] ipAtOpcode = organism.getIp();
        int[] firstArgIp = organism.getNextInstructionPosition(ipAtOpcode, world, organism.getDvBeforeFetch());
        int r1 = world.getSymbol(firstArgIp).value();
        int[] secondArgIp = organism.getNextInstructionPosition(firstArgIp, world, organism.getDvBeforeFetch());
        int r2 = world.getSymbol(secondArgIp).value();
        return new IfrInstruction(organism, r1, r2, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException( "IFR/LTR/GTR erwarten genau 2 Register-Argumente: %REG1 %REG2");
        Integer reg1Id = registerMap.get(args[0].toUpperCase());
        if (reg1Id == null) {
            throw new IllegalArgumentException(String.format("Ungültiges Register-Argument für Reg1: '%s' (erwartet: Registername).", args[0]));
        }
        Integer reg2Id = registerMap.get(args[1].toUpperCase());
        if (reg2Id == null) {
            throw new IllegalArgumentException(String.format("Ungültiges Register-Argument für Reg2: '%s' (erwartet: Registername).", args[1]));
        }
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, reg1Id).toInt(),
                new Symbol(Config.TYPE_DATA, reg2Id).toInt()
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        Object val1Obj = organism.getDr(reg1);
        Object val2Obj = organism.getDr(reg2);

        boolean conditionMet = false;

        // Fall 1: Beide sind Vektoren
        if (val1Obj instanceof int[] v1 && val2Obj instanceof int[] v2) {
            // Vektoren können nur auf Gleichheit geprüft werden (IFR)
            if ((fullOpcodeId & Config.VALUE_MASK) == ID_IFR) {
                conditionMet = Arrays.equals(v1, v2);
            }
        }
        // Fall 2: Beide sind Skalare
        else if (val1Obj instanceof Integer v1Raw && val2Obj instanceof Integer v2Raw) {
            Symbol s1 = Symbol.fromInt(v1Raw);
            Symbol s2 = Symbol.fromInt(v2Raw);

            // KORRIGIERT: Neue Logik für STRICT_TYPING
            if (Config.STRICT_TYPING) {
                // Wenn Typen übereinstimmen, vergleiche Werte.
                if (s1.type() == s2.type()) {
                    conditionMet = performComparison(s1.toScalarValue(), s2.toScalarValue());
                }
                // Wenn Typen nicht übereinstimmen, ist die Bedingung immer 'false', aber es gibt keinen Fehler.
            } else {
                // Ohne Strict Typing werden nur die Werte verglichen.
                conditionMet = performComparison(s1.toScalarValue(), s2.toScalarValue());
            }
        }
        // Fall 3: Alle anderen Fälle (Mischtypen, null) führen zu 'false'.

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
}
// src/main/java/org/evochora/organism/IfiInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.ArgumentType;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.List;
import java.util.Map;

public class IfiInstruction extends Instruction {

    public static final int ID_IFI = 24;
    public static final int ID_LTI = 25;
    public static final int ID_GTI = 26;

    private final int reg1;
    private final int literalValue;
    private final int fullOpcodeId;

    public IfiInstruction(Organism o, int r1, int literal, int fullOpcodeId) {
        super(o);
        this.reg1 = r1;
        this.literalValue = literal;
        this.fullOpcodeId = fullOpcodeId;
    }

    static {
        Instruction.registerInstruction(IfiInstruction.class, ID_IFI, "IFI", 3, IfiInstruction::plan, IfiInstruction::assemble);
        Instruction.registerArgumentTypes(ID_IFI, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.LITERAL));
        Instruction.registerInstruction(IfiInstruction.class, ID_LTI, "LTI", 3, IfiInstruction::plan, IfiInstruction::assemble);
        Instruction.registerArgumentTypes(ID_LTI, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.LITERAL));
        Instruction.registerInstruction(IfiInstruction.class, ID_GTI, "GTI", 3, IfiInstruction::plan, IfiInstruction::assemble);
        Instruction.registerArgumentTypes(ID_GTI, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.LITERAL));
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
        if (argIndex == 0) return ArgumentType.REGISTER;
        if (argIndex == 1) return ArgumentType.LITERAL;
        throw new IllegalArgumentException("Ungültiger Argumentindex für " + getName() + ": " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        int[] ipAtOpcode = organism.getIp();
        int[] firstArgIp = organism.getNextInstructionPosition(ipAtOpcode, world, organism.getDvBeforeFetch());
        int r1 = world.getSymbol(firstArgIp).value();
        int[] secondArgIp = organism.getNextInstructionPosition(firstArgIp, world, organism.getDvBeforeFetch());
        int literal = world.getSymbol(secondArgIp).toInt();
        return new IfiInstruction(organism, r1, literal, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) {
            throw new IllegalArgumentException("IFI/LTI/GTI erwarten genau 2 Argumente: %REG TYPE:WERT");
        }
        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) {
            throw new IllegalArgumentException("Ungültiges Register-Argument: " + args[0]);
        }
        String[] literalParts = args[1].split(":");
        if (literalParts.length != 2) {
            throw new IllegalArgumentException("Literal muss das Format TYPE:WERT haben: " + args[1]);
        }
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

    @Override
    public void execute(Simulation simulation) {
        Object val1Obj = organism.getDr(reg1);
        boolean conditionMet = false;

        // Ein Vektor im Register kann nicht mit einem Skalar-Literal verglichen werden.
        if (val1Obj instanceof Integer v1Raw) {
            Symbol s1 = Symbol.fromInt(v1Raw);
            Symbol s2 = Symbol.fromInt(this.literalValue);

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
        // Fall 2: Alle anderen Fälle (Vektor, null) führen zu 'false'.

        if (!conditionMet) {
            organism.skipNextInstruction(organism.getSimulation().getWorld());
        }
    }

    private boolean performComparison(int v1, int v2) {
        int opcodeValue = this.fullOpcodeId & Config.VALUE_MASK;
        if (opcodeValue == ID_IFI) return v1 == v2;
        if (opcodeValue == ID_LTI) return v1 < v2;
        if (opcodeValue == ID_GTI) return v1 > v2;
        return false;
    }
}
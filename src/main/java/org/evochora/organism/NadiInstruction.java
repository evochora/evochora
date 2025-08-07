// src/main/java/org/evochora/organism/NadiInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.ArgumentType;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.List;
import java.util.Map;

public class NadiInstruction extends Instruction {
    public static final int ID = 32;

    private final int reg1;
    private final int literalValue;

    public NadiInstruction(Organism o, int r1, int literal) {
        super(o);
        this.reg1 = r1;
        this.literalValue = literal;
    }

    static {
        Instruction.registerInstruction(NadiInstruction.class, ID, "NADI", 3, NadiInstruction::plan, NadiInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.LITERAL));
    }

    @Override
    public String getName() {
        return "NADI";
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
        throw new IllegalArgumentException("Ungültiger Argumentindex für NADI: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int r1 = result1.value();

        int[] firstArgIp = result1.nextIp();
        int[] secondArgIp = organism.getNextInstructionPosition(firstArgIp, world, organism.getDvBeforeFetch());

        int literal = world.getSymbol(secondArgIp).toInt();

        return new NadiInstruction(organism, r1, literal);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) {
            throw new IllegalArgumentException("NADI erwartet genau 2 Argumente: %REG TYPE:WERT");
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

        if (val1Obj instanceof int[]) {
            organism.instructionFailed("NADI: Vektoren sind nicht für bitweise Operationen erlaubt.");
            return;
        }

        if (!(val1Obj instanceof Integer v1Raw)) {
            organism.instructionFailed("NADI: Ungültiger Registertyp. Register " + reg1 + " muss ein skalarer Integer sein.");
            return;
        }

        Symbol s1 = Symbol.fromInt(v1Raw);
        Symbol s2 = Symbol.fromInt(this.literalValue);

        if (Config.STRICT_TYPING && s1.type() != s2.type()) {
            organism.instructionFailed("NADI: Register- und Literal-Typen müssen übereinstimmen im strikten Modus.");
            return;
        }

        int v1Value = s1.toScalarValue();
        int v2Value = s2.toScalarValue();

        int resultValue = (~(v1Value & v2Value)) & Config.VALUE_MASK;
        organism.setDr(reg1, new Symbol(s1.type(), resultValue).toInt());
    }
}
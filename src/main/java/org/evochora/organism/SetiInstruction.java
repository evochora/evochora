// src/main/java/org/evochora/organism/SetlInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.ArgumentType;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.List;
import java.util.Map;

public class SetiInstruction extends Instruction {
    public static final int ID = 1;

    private final int registerIndex;
    private final int fullLiteralValue;

    public SetiInstruction(Organism o, int r, int v) {
        super(o);
        this.registerIndex = r;
        this.fullLiteralValue = v;
    }

    static {
        Instruction.registerInstruction(SetiInstruction.class, ID, "SETI", 3, SetiInstruction::plan, SetiInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.LITERAL));
    }

    @Override
    public String getName() {
        return "SETI";
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
        throw new IllegalArgumentException("Ungültiger Argumentindex für SETI: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        // Lese das erste Argument (Register-Index)
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int regIdx = result1.value();

        // KORREKTUR: Berechne die Position des ZWEITEN Arguments, basierend auf der Position des ersten.
        int[] firstArgIp = result1.nextIp();
        int[] secondArgIp = organism.getNextInstructionPosition(firstArgIp, world, organism.getDvBeforeFetch());

        // Lese den Literal-Wert von der Position des ZWEITEN Arguments
        int literalValueWithExplicitType = world.getSymbol(secondArgIp).toInt();

        return new SetiInstruction(organism, regIdx, literalValueWithExplicitType);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SETI erwartet 2 Argumente: %REG TYPE:WERT");

        int regId = registerMap.get(args[0].toUpperCase());
        String[] literalParts = args[1].split(":");
        if (literalParts.length != 2) {
            throw new IllegalArgumentException("SETI-Literal muss das Format TYPE:WERT haben: " + args[1]);
        }
        String typeName = literalParts[0].toUpperCase();
        int value = Integer.parseInt(literalParts[1]);

        int type = switch (typeName) {
            case "CODE" -> Config.TYPE_CODE;
            case "DATA" -> Config.TYPE_DATA;
            case "ENERGY" -> Config.TYPE_ENERGY;
            case "STRUCTURE" -> Config.TYPE_STRUCTURE;
            default -> throw new IllegalArgumentException("Unbekannter Typ für SETI-Literal: " + typeName);
        };

        int fullTypedValue = new Symbol(type, value).toInt();
        // Argumente sind DATA-Typ, damit sie nicht als Opcodes missinterpretiert werden können.
        int typedRegId = new Symbol(Config.TYPE_DATA, regId).toInt();

        return new AssemblerOutput.CodeSequence(List.of(typedRegId, fullTypedValue));
    }

    @Override
    public void execute(Simulation simulation) {
        if (!organism.setDr(registerIndex, this.fullLiteralValue)) {
            organism.instructionFailed("SETI: Failed to set literal value " + fullLiteralValue + " to DR " + registerIndex + ". Possible invalid register index.");
        }
    }
}
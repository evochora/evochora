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

public class SetlInstruction extends Instruction {
    public static final int ID = 1;

    private final int registerIndex;
    private final int fullLiteralValue;

    public SetlInstruction(Organism o, int r, int v) {
        super(o);
        this.registerIndex = r;
        this.fullLiteralValue = v;
    }

    static {
        // Die Länge (3) und der Name ("SETL") werden jetzt direkt übergeben
        Instruction.registerInstruction(SetlInstruction.class, ID, "SETL", 3, SetlInstruction::plan, SetlInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.LITERAL));
    }

    @Override
    public String getName() {
        return "SETL";
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
        return switch (argIndex) {
            case 0 -> ArgumentType.REGISTER;
            case 1 -> ArgumentType.LITERAL;
            default -> throw new IllegalArgumentException("Ungültiger Argumentindex für SETL: " + argIndex);
        };
    }

    public static Instruction plan(Organism organism, World world) {
        // GEÄNDERT: Neue Logik mit FetchResult
        // Erstes Argument (Register-Index) lesen
        Organism.FetchResult regResult = organism.fetchArgument(organism.getIp(), world);
        int regIdx = regResult.value();

        // Zweites Argument (Literal) lesen.
        // Wir brauchen den vollen Integer-Wert des Symbols, nicht nur den 12-bit-Wert.
        // Dafür lesen wir das Symbol an der Position, die uns das erste FetchResult geliefert hat.
        int[] literalArgIp = regResult.nextIp();
        int literalValueWithExplicitType = world.getSymbol(literalArgIp).toInt();

        return new SetlInstruction(organism, regIdx, literalValueWithExplicitType);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SETL erwartet 2 Argumente: %REG TYPE:WERT");

        int regId = registerMap.get(args[0].toUpperCase());
        String[] literalParts = args[1].split(":");
        if (literalParts.length != 2) {
            throw new IllegalArgumentException("SETL-Literal muss das Format TYPE:WERT haben: " + args[1]);
        }
        String typeName = literalParts[0].toUpperCase();
        int value = Integer.parseInt(literalParts[1]);

        int type = switch (typeName) {
            case "CODE" -> Config.TYPE_CODE;
            case "DATA" -> Config.TYPE_DATA;
            case "ENERGY" -> Config.TYPE_ENERGY;
            case "STRUCTURE" -> Config.TYPE_STRUCTURE;
            default -> throw new IllegalArgumentException("Unbekannter Typ für SETL-Literal: " + typeName);
        };

        int fullTypedValue = new Symbol(type, value).toInt();
        int typedRegId = new Symbol(Config.TYPE_DATA, regId).toInt();

        return new AssemblerOutput.CodeSequence(List.of(typedRegId, fullTypedValue));
    }

    @Override
    public void execute(Simulation simulation) {
        if (!organism.setDr(registerIndex, this.fullLiteralValue)) {
            organism.instructionFailed("SETL: Failed to set literal value " + fullLiteralValue + " to DR " + registerIndex + ". Possible invalid register index.");
        }
    }
}
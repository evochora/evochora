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

public class SetiInstruction extends Instruction {

    public static final int LENGTH = 3;

    private final int registerIndex;
    private final int fullLiteralValue;

    public SetiInstruction(Organism o, int r, int v, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.registerIndex = r;
        this.fullLiteralValue = v;
    }

    @Override
    public void execute(Simulation simulation) {
        if (!organism.setDr(registerIndex, this.fullLiteralValue)) {
            organism.instructionFailed("SETI: Failed to set literal value to DR " + registerIndex + ".");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int regIdx = result1.value();
        int[] secondArgIp = organism.getNextInstructionPosition(result1.nextIp(), world, organism.getDvBeforeFetch());
        int literalValueWithExplicitType = world.getSymbol(secondArgIp).toInt();
        return new SetiInstruction(organism, regIdx, literalValueWithExplicitType, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SETI erwartet 2 Argumente: %REG TYPE:WERT");
        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) throw new IllegalArgumentException("Ungültiges Register-Argument: " + args[0]);
        String[] literalParts = args[1].split(":");
        if (literalParts.length != 2) throw new IllegalArgumentException("SETI-Literal muss das Format TYPE:WERT haben: " + args[1]);
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
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt(),
                fullTypedValue
        ));
    }
}
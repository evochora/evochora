package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.Deque;
import java.util.List;
import java.util.Map;

public class PusiInstruction extends Instruction {

    public static final int LENGTH = 2;

    private final int literalValue;

    public PusiInstruction(Organism organism, int literal, int fullOpcodeId) {
        super(organism, fullOpcodeId);
        this.literalValue = literal;
    }

    @Override
    public void execute(Simulation simulation) {
        Deque<Object> stack = organism.getDataStack();
        if (stack.size() >= Config.STACK_MAX_DEPTH) {
            organism.instructionFailed("Stack Overflow");
            return;
        }
        stack.push(this.literalValue);
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int literal = world.getSymbol(result.nextIp()).toInt();
        return new PusiInstruction(organism, literal, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("PUSI erwartet genau 1 Argument: TYPE:WERT");
        String[] literalParts = args[0].split(":");
        if (literalParts.length != 2) throw new IllegalArgumentException("Literal muss das Format TYPE:WERT haben: " + args[0]);
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
                fullLiteralValue
        ));
    }
}
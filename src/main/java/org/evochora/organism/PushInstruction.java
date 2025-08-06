// src/main/java/org/evochora/organism/PushInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.ArgumentType;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.Deque;
import java.util.List;
import java.util.Map;

public class PushInstruction extends Instruction {

    public static final int ID = 22; // Neue ID für PUSH

    private final int srcReg;

    public PushInstruction(Organism organism, int srcReg) {
        super(organism);
        this.srcReg = srcReg;
    }

    static {
        Instruction.registerInstruction(
                PushInstruction.class,
                ID,
                "PUSH",
                2, // Länge: Opcode + 1 Argument (Quell-Register)
                PushInstruction::plan,
                PushInstruction::assemble
        );
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER));
    }

    @Override
    public String getName() {
        return "PUSH";
    }

    @Override
    public int getLength() {
        return 2;
    }

    @Override
    protected int getFixedBaseCost() {
        return 1; // Standardkosten für eine einfache Operation
    }

    @Override
    public int getCost(Organism organism, World world, List<Integer> rawArguments) {
        return getFixedBaseCost();
    }

    @Override
    public ArgumentType getArgumentType(int argIndex) {
        if (argIndex == 0) {
            return ArgumentType.REGISTER;
        }
        throw new IllegalArgumentException("Ungültiger Argumentindex für PUSH: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int registerIndex = result.value();
        return new PushInstruction(organism, registerIndex);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) {
            throw new IllegalArgumentException("PUSH erwartet genau ein Argument: %REG_SRC");
        }
        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) {
            throw new IllegalArgumentException("Ungültiges Register-Argument für PUSH: " + args[0]);
        }
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        Object valueToPush = organism.getDr(this.srcReg);

        // Prüfen, ob das Register gültig war. getDr setzt bei Fehlern bereits die instructionFailed-Flag.
        if (valueToPush == null) {
            return;
        }

        Deque<Object> stack = organism.getDataStack();

        // Prüfen auf "Stack Overflow" gegen das Limit aus der Config
        if (stack.size() >= Config.STACK_MAX_DEPTH) {
            organism.instructionFailed("Stack Overflow");
            return;
        }

        // Den Wert auf den Stack legen
        stack.push(valueToPush);
    }
}
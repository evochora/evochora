// src/main/java/org/evochora/organism/PopInstruction.java
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
import java.util.NoSuchElementException;

public class PopInstruction extends Instruction {

    public static final int ID = 23; // Neue ID für POP

    private final int destReg;

    public PopInstruction(Organism organism, int destReg) {
        super(organism);
        this.destReg = destReg;
    }

    static {
        Instruction.registerInstruction(
                PopInstruction.class,
                ID,
                "POP",
                2, // Länge: Opcode + 1 Argument (Ziel-Register)
                PopInstruction::plan,
                PopInstruction::assemble
        );
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER));
    }

    @Override
    public String getName() {
        return "POP";
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
        throw new IllegalArgumentException("Ungültiger Argumentindex für POP: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int registerIndex = result.value();
        return new PopInstruction(organism, registerIndex);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) {
            throw new IllegalArgumentException("POP erwartet genau ein Argument: %REG_DEST");
        }
        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) {
            throw new IllegalArgumentException("Ungültiges Register-Argument für POP: " + args[0]);
        }
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        Deque<Object> stack = organism.getDataStack();

        try {
            // Versuchen, einen Wert vom Stack zu holen
            Object poppedValue = stack.pop();

            // Bei Erfolg wird der Wert in das Ziel-Register geschrieben.
            // Die setDr-Methode behandelt bereits Fehler bei ungültigem Register-Index.
            organism.setDr(this.destReg, poppedValue);

        } catch (NoSuchElementException e) {
            // Dieser catch-Block fängt den Fehler ab, wenn der Stack leer ist,
            // und meldet ihn als "Stack Underflow".
            organism.instructionFailed("Stack Underflow");
        }
    }
}
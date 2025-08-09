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

public class PushInstruction extends Instruction {

    public static final int LENGTH = 2;

    private final int srcReg;

    public PushInstruction(Organism organism, int srcReg, int fullOpcodeId) {
        super(organism, fullOpcodeId);
        this.srcReg = srcReg;
    }

    @Override
    public void execute(Simulation simulation) {
        Object valueToPush = organism.getDr(this.srcReg);
        if (valueToPush == null) {
            return;
        }
        Deque<Object> stack = organism.getDataStack();
        if (stack.size() >= Config.DS_MAX_DEPTH) {
            organism.instructionFailed("Stack Overflow");
            return;
        }
        stack.push(valueToPush);
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        Organism.FetchResult result = organism.fetchArgument(organism.getIp(), world);
        int registerIndex = result.value();
        return new PushInstruction(organism, registerIndex, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("PUSH erwartet genau ein Argument: %REG_SRC");
        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) throw new IllegalArgumentException("Ungültiges Register-Argument für PUSH: " + args[0]);
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt()
        ));
    }
}
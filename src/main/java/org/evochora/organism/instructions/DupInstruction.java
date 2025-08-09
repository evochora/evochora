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

public class DupInstruction extends Instruction {

    public static final int LENGTH = 1;

    public DupInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        Deque<Object> ds = organism.getDataStack();
        if (ds.isEmpty()) {
            organism.instructionFailed("Stack Underflow: DUP benötigt mindestens 1 Element.");
            return;
        }
        if (ds.size() >= Config.DS_MAX_DEPTH) {
            organism.instructionFailed("Stack Overflow: DUP würde DS-Maximum überschreiten.");
            return;
        }
        Object top = ds.peek();
        ds.push(top);
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        return new DupInstruction(organism, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 0) throw new IllegalArgumentException("DUP erwartet keine Argumente.");
        return new AssemblerOutput.CodeSequence(List.of());
    }
}

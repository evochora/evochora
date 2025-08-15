package org.evochora.runtime.isa.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.compiler.internal.legacy.AssemblerOutput;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class StackInstruction extends Instruction {

    public StackInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        Deque<Object> ds = organism.getDataStack();
        String opName = getName();

        try {
            switch (opName) {
                case "DUP":
                    if (ds.isEmpty()) { organism.instructionFailed("Stack Underflow for DUP."); return; }
                    if (ds.size() >= Config.DS_MAX_DEPTH) { organism.instructionFailed("Stack Overflow for DUP."); return; }
                    ds.push(ds.peek());
                    break;

                case "SWAP":
                    if (ds.size() < 2) { organism.instructionFailed("Stack Underflow for SWAP."); return; }
                    Object a = ds.pop();
                    Object b = ds.pop();
                    ds.push(a);
                    ds.push(b);
                    break;

                case "DROP":
                    if (ds.isEmpty()) { organism.instructionFailed("Stack Underflow for DROP."); return; }
                    ds.pop();
                    break;

                case "ROT":
                    if (ds.size() < 3) { organism.instructionFailed("Stack Underflow for ROT."); return; }
                    Object c = ds.pop();
                    Object b_rot = ds.pop();
                    Object a_rot = ds.pop();
                    ds.push(b_rot);
                    ds.push(c);
                    ds.push(a_rot);
                    break;

                default:
                    organism.instructionFailed("Unknown stack instruction: " + opName);
            }
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during " + opName);
        }
    }

    public static Instruction plan(Organism organism, Environment environment) {
        int fullOpcodeId = environment.getMolecule(organism.getIp()).toInt();
        return new StackInstruction(organism, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap, String instructionName) {
        if (args.length != 0) {
            throw new IllegalArgumentException(instructionName.toUpperCase() + " expects no arguments.");
        }
        return new AssemblerOutput.CodeSequence(List.of());
    }
}

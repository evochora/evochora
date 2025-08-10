package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class ControlFlowInstruction extends Instruction {

    public ControlFlowInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        String opName = getName();
        try {
            switch (opName) {
                case "JMPI", "CALL" -> {
                    List<Operand> operands = resolveOperands(simulation.getWorld());
                    int[] delta = (int[]) operands.get(0).value();
                    int[] targetIp = organism.getTargetCoordinate(organism.getIpBeforeFetch(), delta, simulation.getWorld());

                    if (opName.equals("CALL")) {
                        pushReturnStackFrame(simulation.getWorld());
                    }
                    organism.setIp(targetIp);
                    organism.setSkipIpAdvance(true);
                }
                case "JMPR" -> {
                    List<Operand> operands = resolveOperands(simulation.getWorld());
                    int[] relativeCoord = (int[]) operands.get(0).value();
                    int[] initialPosition = organism.getInitialPosition();
                    int[] absoluteCoord = new int[relativeCoord.length];
                    for (int i = 0; i < relativeCoord.length; i++) {
                        absoluteCoord[i] = initialPosition[i] + relativeCoord[i];
                    }
                    organism.setIp(absoluteCoord);
                    organism.setSkipIpAdvance(true);
                }
                case "JMPS" -> {
                    List<Operand> operands = resolveOperands(simulation.getWorld());
                    int[] vec = (int[]) operands.get(0).value();
                    int[] target = organism.getTargetCoordinate(organism.getIpBeforeFetch(), vec, simulation.getWorld());
                    organism.setIp(target);
                    organism.setSkipIpAdvance(true);
                }
                case "RET" -> {
                    popReturnStackFrame();
                }
                default -> organism.instructionFailed("Unknown control flow instruction: " + opName);
            }
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during control flow operation.");
        } catch (ClassCastException e) {
            organism.instructionFailed("Invalid operand type for control flow operation.");
        }
    }

    private void pushReturnStackFrame(World world) {
        int[] absoluteReturnIp = organism.getIpBeforeFetch();
        for (int i = 0; i < getLength(); i++) {
            absoluteReturnIp = organism.getNextInstructionPosition(absoluteReturnIp, world, organism.getDvBeforeFetch());
        }
        int[] initialPosition = organism.getInitialPosition();
        int[] relativeReturnIp = new int[absoluteReturnIp.length];
        for (int i = 0; i < absoluteReturnIp.length; i++) {
            relativeReturnIp[i] = absoluteReturnIp[i] - initialPosition[i];
        }
        Deque<Object> rs = organism.getReturnStack();
        if (rs.size() >= Config.RS_MAX_DEPTH) {
            organism.instructionFailed("Return stack overflow");
            return;
        }
        Object[] prSnapshot = organism.getPrs().toArray(new Object[0]);
        rs.push(new Organism.ProcFrame(relativeReturnIp, prSnapshot));
    }

    private void popReturnStackFrame() {
        Deque<Object> rs = organism.getReturnStack();
        Object poppedValue = rs.pop();
        int[] relativeReturnIp;

        if (poppedValue instanceof Organism.ProcFrame frame) {
            organism.restorePrs(frame.savedPrs);
            relativeReturnIp = frame.relativeReturnIp;
        } else if (poppedValue instanceof int[] legacyReturnIp) {
            relativeReturnIp = legacyReturnIp;
        } else {
            organism.instructionFailed("Invalid value on return stack.");
            return;
        }

        int[] initialPosition = organism.getInitialPosition();
        int[] absoluteReturnIp = new int[relativeReturnIp.length];
        for (int i = 0; i < relativeReturnIp.length; i++) {
            absoluteReturnIp[i] = initialPosition[i] + relativeReturnIp[i];
        }
        organism.setIp(absoluteReturnIp);
        organism.setSkipIpAdvance(true);
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        return new ControlFlowInstruction(organism, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap, String instructionName) {
        String name = instructionName.toUpperCase();

        switch (name) {
            case "JMPI", "CALL":
                if (args.length != 1) throw new IllegalArgumentException(name + " expects 1 label argument.");
                return new AssemblerOutput.JumpInstructionRequest(args[0]);
            case "JMPR":
                if (args.length != 1) throw new IllegalArgumentException("JMPR expects 1 register argument.");
                Integer regId = resolveRegToken(args[0], registerMap);
                if (regId == null) throw new IllegalArgumentException("Invalid register for JMPR.");
                return new AssemblerOutput.CodeSequence(List.of(new Symbol(Config.TYPE_DATA, regId).toInt()));
            case "JMPS":
            case "RET":
                if (args.length != 0) throw new IllegalArgumentException(name + " expects no arguments.");
                return new AssemblerOutput.CodeSequence(Collections.emptyList());
        }
        throw new IllegalArgumentException("Cannot assemble unknown control flow instruction: " + name);
    }
}

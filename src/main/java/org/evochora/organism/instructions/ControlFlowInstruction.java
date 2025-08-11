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

    // VM-internal registry for DR<->FPR bindings at CALL sites and return sites
    private static final class CoordKey {
        private final int[] coord;
        CoordKey(int[] c) { this.coord = java.util.Arrays.copyOf(c, c.length); }
        @Override public boolean equals(Object o) {
            return (this == o) || (o instanceof CoordKey k && java.util.Arrays.equals(this.coord, k.coord));
        }
        @Override public int hashCode() { return java.util.Arrays.hashCode(coord); }
    }
    private static final java.util.Map<CoordKey, int[]> CALL_BINDINGS_BY_CALL_COORD = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<CoordKey, int[]> CALL_BINDINGS_BY_RETURN_COORD = new java.util.concurrent.ConcurrentHashMap<>();

    public static void registerCallBindingForCoord(int[] callCoord, int[] drIds) {
        CALL_BINDINGS_BY_CALL_COORD.put(new CoordKey(callCoord), java.util.Arrays.copyOf(drIds, drIds.length));
    }

    private static int[] getBindingForCallCoord(int[] callCoord) {
        int[] ids = CALL_BINDINGS_BY_CALL_COORD.get(new CoordKey(callCoord));
        return ids == null ? null : java.util.Arrays.copyOf(ids, ids.length);
    }

    private static void registerReturnBinding(int[] returnCoord, int[] drIds) {
        CALL_BINDINGS_BY_RETURN_COORD.put(new CoordKey(returnCoord), java.util.Arrays.copyOf(drIds, drIds.length));
    }

    private static int[] getBindingForReturnCoord(int[] returnCoord) {
        int[] ids = CALL_BINDINGS_BY_RETURN_COORD.remove(new CoordKey(returnCoord));
        return ids == null ? null : java.util.Arrays.copyOf(ids, ids.length);
    }

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
                    int[] callIp = organism.getIpBeforeFetch();
                    int[] targetIp = organism.getTargetCoordinate(callIp, delta, simulation.getWorld());

                    if (opName.equals("CALL")) {
                        // Copy-in: DR -> FPR based on binding registered for this CALL coordinate
                        int[] binding = getBindingForCallCoord(callIp);
                        if (binding != null) {
                            for (int i = 0; i < binding.length; i++) {
                                Object val = readOperand(binding[i]);
                                organism.setFpr(i, val);
                            }
                        }
                        // Compute absolute return IP to enable copy-back on RET
                        int[] absoluteReturnIp = callIp;
                        for (int i = 0; i < getLength(); i++) {
                            absoluteReturnIp = organism.getNextInstructionPosition(absoluteReturnIp, simulation.getWorld(), organism.getDvBeforeFetch());
                        }
                        if (binding != null) {
                            registerReturnBinding(absoluteReturnIp, binding);
                        }
                        // Push return frame (restores PRs on RET)
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
                    // Pop frame (restores PRs) and determine return IP for copy-back
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

                    // Copy-back: FPR -> DR for the binding registered for this return coordinate
                    int[] binding = getBindingForReturnCoord(absoluteReturnIp);
                    if (binding != null) {
                        for (int i = 0; i < binding.length; i++) {
                            Object val = organism.getFpr(i);
                            // writeOperand routes to the correct register file based on id
                            writeOperand(binding[i], val);
                        }
                    }

                    organism.setIp(absoluteReturnIp);
                    organism.setSkipIpAdvance(true);
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
            // Restore PRs on return as required by calling convention
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

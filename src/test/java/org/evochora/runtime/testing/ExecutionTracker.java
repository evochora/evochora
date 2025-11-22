package org.evochora.runtime.testing;

import org.evochora.runtime.Simulation;
import org.evochora.runtime.VirtualMachine;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Helper class to track instruction execution and register states during test execution.
 * <p>
 * This utility is useful for debugging and analyzing runtime behavior in tests.
 * It captures the state of an organism before and after each simulation tick,
 * including which instructions were executed and how registers changed.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * ExecutionTracker tracker = new ExecutionTracker(sim, org);
 * for (int i = 0; i < 10; i++) {
 *     tracker.beforeTick();
 *     sim.tick();
 *     tracker.afterTick();
 * }
 * String trace = tracker.getTraceAsString();
 * System.out.println(trace);
 * }
 * </pre>
 */
public class ExecutionTracker {
    private final Simulation sim;
    private final Organism org;
    private final VirtualMachine vm;
    private final List<TickSnapshot> snapshots = new ArrayList<>();

    private static class TickSnapshot {
        final long tick;
        final String instructionName;
        final int opcodeId;
        final boolean executed;
        final RegisterState registersBefore;
        final RegisterState registersAfter;
        final int[] ip;
        final int[] ipBeforeFetch;

        TickSnapshot(long tick, String instructionName, int opcodeId, boolean executed,
                    RegisterState registersBefore, RegisterState registersAfter,
                    int[] ip, int[] ipBeforeFetch) {
            this.tick = tick;
            this.instructionName = instructionName;
            this.opcodeId = opcodeId;
            this.executed = executed;
            this.registersBefore = registersBefore;
            this.registersAfter = registersAfter;
            this.ip = ip;
            this.ipBeforeFetch = ipBeforeFetch;
        }
    }

    private static class RegisterState {
        final List<Object> drs;
        final List<Object> prs;
        final List<Object> fprs;
        final int er;

        RegisterState(Organism org) {
            this.drs = new ArrayList<>(org.getDrs());
            this.prs = new ArrayList<>(org.getPrs());
            this.fprs = new ArrayList<>(org.getFprs());
            this.er = org.getEr();
        }
    }

    /**
     * Creates a new ExecutionTracker for the given simulation and organism.
     *
     * @param sim The simulation to track.
     * @param org The organism to track.
     */
    public ExecutionTracker(Simulation sim, Organism org) {
        this.sim = sim;
        this.org = org;
        this.vm = sim.getVirtualMachine();
    }

    /**
     * Call this method before each simulation tick to capture the state before execution.
     */
    public void beforeTick() {
        // Capture state before tick
        RegisterState before = new RegisterState(org);
        // The IP position BEFORE the tick is the current IP (which will be used to plan the instruction)
        int[] ipBeforeTick = org.getIp();
        // ipBeforeFetch will be set during vm.plan() in sim.tick(), so we'll capture it after
        int[] ipBeforeFetch = org.getIpBeforeFetch();
        
        // Determine instruction by reading molecule at IP position BEFORE the tick
        // This is the instruction that will be planned and executed in this tick
        Molecule molecule = sim.getEnvironment().getMolecule(ipBeforeTick);
        int opcodeId = molecule.toInt();
        String name = Instruction.getInstructionNameById(opcodeId);
        if (name == null || name.equals("UNKNOWN")) {
            name = "UNKNOWN(" + opcodeId + ")";
        }
        
        // Note: We can't know if it will be executed until after conflict resolution
        // So we'll capture that in afterTick
        snapshots.add(new TickSnapshot(
            sim.getCurrentTick(),
            name,
            opcodeId,
            false, // will be updated in afterTick
            before,
            null, // will be set in afterTick
            ipBeforeTick, // IP before tick (where instruction is read from)
            ipBeforeFetch // IP before fetch (set during planning)
        ));
    }

    /**
     * Call this method after each simulation tick to capture the state after execution.
     */
    public void afterTick() {
        // Update the last snapshot with execution status and final state
        if (!snapshots.isEmpty()) {
            TickSnapshot last = snapshots.get(snapshots.size() - 1);
            
            // Get the IP before fetch (which was set during vm.plan() in sim.tick())
            int[] ipBeforeFetch = org.getIpBeforeFetch();
            
            // Check if instruction was actually executed by looking at IP change
            // If IP changed, instruction was likely executed
            int[] currentIp = org.getIp();
            boolean executed = !Arrays.equals(last.ip, currentIp) || 
                              last.instructionName.equals("NOP") ||
                              last.instructionName.equals("RET");
            
            RegisterState after = new RegisterState(org);
            
            // Replace with updated snapshot
            snapshots.set(snapshots.size() - 1, new TickSnapshot(
                last.tick,
                last.instructionName,
                last.opcodeId,
                executed,
                last.registersBefore,
                after,
                currentIp, // IP after tick (where we are now)
                ipBeforeFetch // IP before fetch (set during planning in this tick)
            ));
        }
    }

    /**
     * Prints the execution trace to System.out.
     */
    public void printTrace() {
        System.out.println(getTraceAsString());
    }

    /**
     * Returns the execution trace as a formatted string.
     *
     * @return A formatted string containing the execution trace.
     */
    public String getTraceAsString() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== EXECUTION TRACE ===\n");
        for (TickSnapshot snapshot : snapshots) {
            sb.append(String.format("\nTick %d: %s (opcode=%d, executed=%s)\n",
                snapshot.tick, snapshot.instructionName, snapshot.opcodeId, snapshot.executed));
            sb.append(String.format("  IP: %s -> %s\n",
                Arrays.toString(snapshot.ipBeforeFetch), Arrays.toString(snapshot.ip)));
            
            // Show what's actually in the environment at the IP position
            Molecule moleculeAtIp = sim.getEnvironment().getMolecule(snapshot.ipBeforeFetch);
            sb.append(String.format("  Environment at IP: type=%d, value=%d (opcode=%d)\n",
                moleculeAtIp.type(), moleculeAtIp.toInt(), moleculeAtIp.toInt()));
            
            if (snapshot.registersBefore != null) {
                sb.append("  Registers BEFORE:\n");
                appendRegisters(sb, snapshot.registersBefore);
            }
            
            if (snapshot.registersAfter != null) {
                sb.append("  Registers AFTER:\n");
                appendRegisters(sb, snapshot.registersAfter);
            }
        }
        sb.append("\n=== END TRACE ===\n");
        return sb.toString();
    }

    private void appendRegisters(StringBuilder sb, RegisterState state) {
        sb.append(String.format("    ER: %d\n", state.er));
        if (!state.drs.isEmpty()) {
            sb.append("    DRs: ");
            for (int i = 0; i < state.drs.size(); i++) {
                Object dr = state.drs.get(i);
                if (dr != null) {
                    Molecule m = Molecule.fromInt((Integer) dr);
                    sb.append(String.format("DR%d=%d ", i, m.toScalarValue()));
                }
            }
            sb.append("\n");
        }
        if (!state.prs.isEmpty()) {
            sb.append("    PRs: ");
            for (int i = 0; i < state.prs.size(); i++) {
                Object pr = state.prs.get(i);
                if (pr != null) {
                    Molecule m = Molecule.fromInt((Integer) pr);
                    sb.append(String.format("PR%d=%d ", i, m.toScalarValue()));
                }
            }
            sb.append("\n");
        }
        if (!state.fprs.isEmpty()) {
            sb.append("    FPRs: ");
            for (int i = 0; i < state.fprs.size(); i++) {
                Object fpr = state.fprs.get(i);
                if (fpr != null) {
                    Molecule m = Molecule.fromInt((Integer) fpr);
                    sb.append(String.format("FPR%d=%d ", i, m.toScalarValue()));
                }
            }
            sb.append("\n");
        }
    }
}


package org.evochora.runtime.isa.instructions;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.IEnvironmentModifyingInstruction;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import static org.evochora.runtime.isa.Instruction.OperandSource.*;

/**
 * Handles environment interaction instructions like POKE and PEEK.
 * Implements the IEnvironmentModifyingInstruction interface, which means it's
 * involved in conflict resolution.
 */
public class EnvironmentInteractionInstruction extends Instruction implements IEnvironmentModifyingInstruction {

    private static int family;

    /** What a cell holds after its molecule has been taken out of it. */
    private static final Molecule EMPTY = new Molecule(Config.TYPE_CODE, 0);

    /**
     * Registers all environment interaction instructions with the instruction registry.
     *
     * @param f the family ID for this instruction family
     */
    public static void register(int f) {
        family = f;
        // Operation 0: PEEK (read value from environment cell)
        reg(0, 0, "PEEK", REGISTER, REGISTER);
        reg(0, 1, "PEKI", REGISTER, VECTOR);
        reg(0, 2, "PEKS", STACK);
        // Operation 1: POKE (write value to environment cell)
        reg(1, 3, "POKE", REGISTER, REGISTER);
        reg(1, 4, "POKI", REGISTER, VECTOR);
        reg(1, 5, "POKS", STACK, STACK);
        // Operation 2: PPK (combined PEEK+POKE)
        reg(2, 6, "PPKR", REGISTER, REGISTER);
        reg(2, 7, "PPKI", REGISTER, VECTOR);
        reg(2, 8, "PPKS", STACK, STACK);
    }

    private static void reg(int op, int index, String name, OperandSource... sources) {
        Instruction.registerOp(EnvironmentInteractionInstruction.class, EnvironmentInteractionInstruction::new,
                family, op, index, name, false, sources);
    }

    private int[] targetCoordinate;

    /**
     * Constructs a new EnvironmentInteractionInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public EnvironmentInteractionInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context) {
        Organism organism = context.getOrganism();
        try {
            String opName = getName();

            if ("POKE".equals(opName) || "POKI".equals(opName) || "POKS".equals(opName)) {
                handlePoke(context);
            } else if ("PEEK".equals(opName) || "PEKI".equals(opName) || "PEKS".equals(opName)) {
                handlePeek(context);
            } else if ("PPKR".equals(opName) || "PPKI".equals(opName) || "PPKS".equals(opName)) {
                handlePeekPoke(context);
            } else {
                organism.instructionFailed("Unknown world interaction instruction: " + opName);
            }

        } catch (NoSuchElementException e) {
            organism.instructionFailed("Invalid operands for " + getName());
        } catch (ClassCastException | ArrayIndexOutOfBoundsException e) {
            organism.instructionFailed("Invalid operand types for world interaction.");
        }
    }

    private void handlePoke(ExecutionContext context) {
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();
        List<Operand> operands = resolveOperands(environment);
        // An instruction the planning phase already failed claims no cell in conflict resolution,
        // so it must not reach the environment: its write would bypass the arbitration that every
        // other write goes through.
        if (organism.isInstructionFailed()) {
            return;
        }
        if (operands.size() < 2) {
            organism.instructionFailed("Invalid operands for " + getName() + ".");
            return;
        }
        Object valueToWrite = operands.get(0).value();

        if (targetCoordinate(environment) == null) {
            return;
        }

        if (getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
            if (valueToWrite instanceof int[]) {
                organism.instructionFailed("POKE: Cannot write vectors to the world.");
                return;
            }
            Molecule toWrite = Molecule.fromInt(Molecule.storedFormOfWrite((Integer) valueToWrite, organism.getMr()));

            if (environment.getMoleculeIntAt(targetCoordinate) == 0) {
                // CODE:0 should always have owner=0 (represents empty cell)
                int ownerId = (toWrite.type() == Config.TYPE_CODE && toWrite.toScalarValue() == 0) ? 0 : organism.getId();
                environment.setMoleculeAt(targetCoordinate, toWrite, ownerId);
                // The cell was empty, so it was unowned; the write is priced for what now stands in it.
                context.recordWrite(toWrite.toInt(), 0);
            } else {
                organism.instructionFailed("POKE: Target cell is not empty.");
                if (getConflictStatus() != ConflictResolutionStatus.NOT_APPLICABLE) setConflictStatus(ConflictResolutionStatus.LOST_TARGET_OCCUPIED);
            }
        }
    }

    private void handlePeek(ExecutionContext context) {
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();
        List<Operand> operands = resolveOperands(environment);
        // An instruction the planning phase already failed claims no cell in conflict resolution,
        // so it must not reach the environment: its write would bypass the arbitration that every
        // other write goes through.
        if (organism.isInstructionFailed()) {
            return;
        }
        int targetReg;

        if (getName().endsWith("S")) {
            if (operands.size() != 1) { organism.instructionFailed("Invalid operands for " + getName()); return; }
            targetReg = -1;
        } else {
            if (operands.size() != 2) { organism.instructionFailed("Invalid operands for " + getName()); return; }
            targetReg = operands.get(0).rawSourceId();
        }

        if (targetCoordinate(environment) == null) {
            return;
        }

        int taken = environment.getMoleculeIntAt(targetCoordinate);

        if (taken == 0) {
            organism.instructionFailed("PEEK: Target cell is empty.");
            return;
        }

        // The register receives what stood in the cell. What of it reaches the organism is the
        // policy's business: an ENERGY molecule beyond the energy register's room is clamped
        // there, and the difference is lost - the cell is emptied either way.
        Object valueToStore = taken;
        int ownerId = environment.getOwnerIdAt(targetCoordinate);

        if (targetReg != -1) {
            writeOperand(targetReg, valueToStore);
        } else if (!organism.pushData(valueToStore)) {
            // A value that cannot be delivered must leave the cell untouched: clearing it here
            // would destroy the molecule and charge the organism for a read it did not get.
            // The guard cannot trigger as the tick stands - the virtual machine pops an
            // instruction's stack operands before executing it, and this variant pops one slot
            // and pushes one - but the order it relies on lives in another class.
            return;
        }

        environment.setMoleculeAt(targetCoordinate, EMPTY, 0);
        context.recordRead(taken, ownerId);
    }

    private void handlePeekPoke(ExecutionContext context) {
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();
        List<Operand> operands = resolveOperands(environment);
        // An instruction the planning phase already failed claims no cell in conflict resolution,
        // so it must not reach the environment: its write would bypass the arbitration that every
        // other write goes through.
        if (organism.isInstructionFailed()) {
            return;
        }
        if (operands.size() < 2) {
            organism.instructionFailed("Invalid operands for " + getName() + ".");
            return;
        }
        // The stack variant writes back to the stack, the others to the register they read from.
        int targetReg = "PPKS".equals(getName()) ? -1 : operands.get(0).rawSourceId();
        Object valueToWrite = operands.get(0).value();

        if (targetCoordinate(environment) == null) {
            return;
        }


        if (getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
            
            // First, handle the PEEK part
            int current = environment.getMoleculeIntAt(targetCoordinate);
            int currentOwnerId = environment.getOwnerIdAt(targetCoordinate);

            // The register receives what stood in the cell, of whatever type; an empty cell gives
            // CODE:0, which is what it holds, and is not a read at all, because nothing was
            // consumed.
            Object valueToStore = current;

            // Store the peeked value (or empty molecule if cell was empty)
            if (targetReg != -1) {
                writeOperand(targetReg, valueToStore);
            } else if (!organism.pushData(valueToStore)) {
                // As in PEEK: a value that cannot be delivered leaves the cell untouched. The
                // guard cannot trigger as the tick stands, because this variant pops two stack
                // slots before execution and pushes one.
                return;
            }

            // Clear the cell (if it wasn't already empty)
            if (current != 0) {
                environment.setMoleculeAt(targetCoordinate, EMPTY, 0);
                context.recordRead(current, currentOwnerId);
            }

            // Now handle the POKE part
            if (valueToWrite instanceof int[]) {
                organism.instructionFailed("PPK: Cannot write vectors to the world.");
                return;
            }
            Molecule toWrite = Molecule.fromInt(Molecule.storedFormOfWrite((Integer) valueToWrite, organism.getMr()));

            // Write the new value (cell is now empty, so this should always succeed)
            // CODE:0 should always have owner=0 (represents empty cell)
            int ownerId = (toWrite.type() == Config.TYPE_CODE && toWrite.toScalarValue() == 0) ? 0 : organism.getId();
            environment.setMoleculeAt(targetCoordinate, toWrite, ownerId);
            // The peek left the cell empty and unowned, whatever stood in it before.
            context.recordWrite(toWrite.toInt(), 0);
        }
    }


    /**
     * The cell this instruction addresses: the active data pointer displaced by the direction its
     * operands name.
     * <p>
     * Every variant of this family takes its vector as the last operand, and that vector is mapped
     * to a displacement the instruction may use, so the cell is the one the data pointer stands on
     * or one adjacent to it. The result is derived once and kept in {@code this.targetCoordinate},
     * which is what makes conflict resolution and execution address the same cell.
     * <p>
     * Three things leave the coordinate underived, and they are answered differently. An operand
     * that is not a vector — a register or a stack slot may hold a scalar — names no cell, and that
     * is failed here, because nothing else would. So is a cell that does not exist: in a bounded
     * world the data pointer may have left the world, or the step from it may lead across the edge.
     * Too few operands is left to the handler, which knows what its own variant expected. Conflict
     * resolution reads an instruction without a target as one that runs and fails on its own.
     *
     * @param environment The environment the coordinate is resolved in.
     * @return The target coordinate, which lies within the world, or {@code null} when the operands
     *         name no direction or no cell of the world.
     */
    private int[] targetCoordinate(Environment environment) {
        if (this.targetCoordinate != null) {
            return this.targetCoordinate;
        }
        // resolveOperands is idempotent; in the plan phase it has already run.
        List<Operand> operands = resolveOperands(environment);
        if (operands.isEmpty()) {
            // Too few operands to hold a vector at all. The handler names what its variant expected.
            return null;
        }
        if (!(operands.get(operands.size() - 1).value() instanceof int[] vector)) {
            // A register or a stack slot may hold a scalar, and then the operand names no cell.
            // Nobody has booked that yet, so it is booked here rather than passing silently.
            organism.instructionFailed(getName() + " requires a vector operand.");
            return null;
        }
        int[] displacement = organism.toDisplacement(vector);
        if (displacement == null) {
            return null;
        }
        int[] dp = organism.getActiveDp();
        if (!liesWithinWorld(dp, environment)) {
            organism.instructionFailed(getName() + ": Data pointer " + Arrays.toString(dp)
                    + " lies outside the world.");
            return null;
        }
        int[] target = organism.getTargetCoordinate(dp, displacement, environment);
        if (!liesWithinWorld(target, environment)) {
            organism.instructionFailed(getName() + ": Target cell " + Arrays.toString(target)
                    + " lies outside the world.");
            return null;
        }
        this.targetCoordinate = target;
        return this.targetCoordinate;
    }

    /**
     * Tells whether a coordinate names a cell of the world.
     *
     * @param coordinate  the coordinate, one component per dimension
     * @param environment the environment whose world is asked about
     * @return {@code true} if every component lies within the world's size along its dimension
     */
    private static boolean liesWithinWorld(int[] coordinate, Environment environment) {
        for (int i = 0; i < coordinate.length; i++) {
            if (coordinate[i] < 0 || coordinate[i] >= environment.properties.getDimensionSize(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the target coordinate for conflict resolution, as a list of the one cell this
     * instruction claims, or an empty list when its operands name no direction.
     * <p>
     * <b>Invocation Order Dependency:</b> The coordinate is derived from the operands resolved in
     * the plan phase and kept from then on. If an
     * {@link org.evochora.runtime.spi.IInstructionInterceptor} modifies operands, it must do so
     * BEFORE this method is called. The current tick cycle guarantees this:
     * <ol>
     *   <li>Plan phase: {@code vm.plan()} calls {@code resolveOperands()}</li>
     *   <li>Interception: Interceptors may modify operands</li>
     *   <li>Conflict resolution: {@code resolveConflicts()} calls this method, which derives and keeps the coordinate</li>
     *   <li>Execute phase: the instruction executes against that same coordinate</li>
     * </ol>
     * <p>
     * <b>Warning:</b> Do not call this method before interceptors have run, as operand
     * modifications afterwards will not affect the target coordinate.
     *
     * @return List containing the single target coordinate, or empty list when there is none
     */
    @Override
    public List<int[]> getTargetCoordinates() {
        int[] coordinate = targetCoordinate(organism.getSimulation().getEnvironment());
        return coordinate == null ? List.of() : List.of(coordinate);
    }
}
package org.evochora.runtime.isa.instructions;

import java.util.List;
import java.util.NoSuchElementException;

import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.IEnvironmentModifyingInstruction;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Variant;
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

    /**
     * Registers all environment interaction instructions with the instruction registry.
     *
     * @param f the family ID for this instruction family
     */
    public static void register(int f) {
        family = f;
        // Operation 0: PEEK (read value from environment cell)
        reg(0, Variant.RR, "PEEK", REGISTER, REGISTER);
        reg(0, Variant.RV, "PEKI", REGISTER, VECTOR);
        reg(0, Variant.S, "PEKS", STACK);
        // Operation 1: POKE (write value to environment cell)
        reg(1, Variant.RR, "POKE", REGISTER, REGISTER);
        reg(1, Variant.RV, "POKI", REGISTER, VECTOR);
        reg(1, Variant.SS, "POKS", STACK, STACK);
        // Operation 2: PPK (combined PEEK+POKE)
        reg(2, Variant.RR, "PPKR", REGISTER, REGISTER);
        reg(2, Variant.RV, "PPKI", REGISTER, VECTOR);
        reg(2, Variant.SS, "PPKS", STACK, STACK);
    }

    private static void reg(int op, int variant, String name, OperandSource... sources) {
        Instruction.registerOp(EnvironmentInteractionInstruction.class, EnvironmentInteractionInstruction::new,
                family, op, variant, name, false, sources);
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
        Object valueToWrite;

        if ("POKS".equals(getName())) {
            if (operands.size() < 2) { organism.instructionFailed("Invalid operands for POKS."); return; }
        } else {
            if (operands.size() < 2) { organism.instructionFailed("Invalid operands for POKE/POKI."); return; }
        }
        valueToWrite = operands.get(0).value();

        if (targetCoordinate(environment) == null) {
            return;
        }

        if (getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
            if (valueToWrite instanceof int[]) {
                organism.instructionFailed("POKE: Cannot write vectors to the world.");
                return;
            }
            Molecule toWrite = Molecule.fromInt(Molecule.storedFormOfWrite((Integer) valueToWrite, organism.getMr()));

            // Energy costs and entropy dissipation are now handled by the thermodynamic policy in VirtualMachine

            if (environment.getMolecule(targetCoordinate).isEmpty()) {
                // CODE:0 should always have owner=0 (represents empty cell)
                int ownerId = (toWrite.type() == Config.TYPE_CODE && toWrite.toScalarValue() == 0) ? 0 : organism.getId();
                environment.setMolecule(toWrite, ownerId, targetCoordinate);
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

        Molecule s = environment.getMolecule(targetCoordinate);

        if (s.isEmpty()) {
            organism.instructionFailed("PEEK: Target cell is empty.");
            return;
        }

        // Store the actual value read from the environment.
        // Energy gains for the organism are handled separately by the thermodynamic policy.
        Object valueToStore = s.toInt();

        if (targetReg != -1) {
            writeOperand(targetReg, valueToStore);
        } else if (!organism.pushData(valueToStore)) {
            return;
        }

        environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), targetCoordinate);
        environment.clearOwner(targetCoordinate);
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
        int targetReg;
        Object valueToWrite;

        if ("PPKS".equals(getName())) {
            if (operands.size() < 2) { organism.instructionFailed("Invalid operands for PPKS."); return; }
            targetReg = -1; // Stack operation
        } else if ("PPKI".equals(getName())) {
            if (operands.size() < 2) { organism.instructionFailed("Invalid operands for PPKI."); return; }
            targetReg = operands.get(0).rawSourceId();
        } else {
            if (operands.size() < 2) { organism.instructionFailed("Invalid operands for PPKR."); return; }
            targetReg = operands.get(0).rawSourceId();
        }
        // The register read and the value written back are the same operand in every variant.
        valueToWrite = operands.get(0).value();

        if (targetCoordinate(environment) == null) {
            return;
        }


        if (getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
            
            // First, handle the PEEK part
            Molecule currentMolecule = environment.getMolecule(targetCoordinate);
            
            
            Object valueToStore;
            if (currentMolecule.isEmpty()) {
                // If cell is empty, store empty molecule (CODE:0)
                valueToStore = new Molecule(Config.TYPE_CODE, 0).toInt();
            } else {
                // Energy costs and gains are now handled by the thermodynamic policy in VirtualMachine
                if (currentMolecule.type() == Config.TYPE_ENERGY) {
                    int energyToTake = Math.min(currentMolecule.toScalarValue(), organism.getMaxEnergy() - organism.getEr());
                    valueToStore = new Molecule(Config.TYPE_ENERGY, energyToTake).toInt();
                } else {
                    valueToStore = currentMolecule.toInt();
                }
            }

            // Store the peeked value (or empty molecule if cell was empty)
            if (targetReg != -1) {
                writeOperand(targetReg, valueToStore);
            } else if (!organism.pushData(valueToStore)) {
                return;
            }

            // Clear the cell (if it wasn't already empty)
            if (!currentMolecule.isEmpty()) {
                environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), targetCoordinate);
                environment.clearOwner(targetCoordinate);
            }

            // Now handle the POKE part
            if (valueToWrite instanceof int[]) {
                organism.instructionFailed("PPK: Cannot write vectors to the world.");
                return;
            }
            Molecule toWrite = Molecule.fromInt(Molecule.storedFormOfWrite((Integer) valueToWrite, organism.getMr()));

            // Energy costs and entropy dissipation are now handled by the thermodynamic policy in VirtualMachine

            // Write the new value (cell is now empty, so this should always succeed)
            // CODE:0 should always have owner=0 (represents empty cell)
            int ownerId = (toWrite.type() == Config.TYPE_CODE && toWrite.toScalarValue() == 0) ? 0 : organism.getId();
            environment.setMolecule(toWrite, ownerId, targetCoordinate);
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
     * Operands that hold no vector at all leave the coordinate underived. That is not decided here:
     * the handler reports what is wrong with its own operands, and conflict resolution reads an
     * instruction without a target as one that runs and fails on its own.
     *
     * @param environment The environment the coordinate is resolved in.
     * @return The target coordinate, or {@code null} when the operands name no direction.
     */
    private int[] targetCoordinate(Environment environment) {
        if (this.targetCoordinate != null) {
            return this.targetCoordinate;
        }
        // resolveOperands is idempotent; in the plan phase it has already run.
        List<Operand> operands = resolveOperands(environment);
        if (operands.isEmpty()
                || !(operands.get(operands.size() - 1).value() instanceof int[] vector)) {
            return null;
        }
        int[] displacement = organism.toDisplacement(vector);
        if (displacement == null) {
            return null;
        }
        this.targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), displacement, environment);
        return this.targetCoordinate;
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
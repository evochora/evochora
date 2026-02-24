package org.evochora.runtime.isa.instructions;

import java.util.List;
import java.util.NoSuchElementException;

import org.evochora.compiler.api.ProgramArtifact;
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
    public void execute(ExecutionContext context, ProgramArtifact artifact) {
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
        Object valueToWrite;
        int[] vector;

        if ("POKS".equals(getName())) {
            if (operands.size() < 2) { organism.instructionFailed("Invalid operands for POKS."); return; }
            valueToWrite = operands.get(0).value();
            vector = (int[]) operands.get(1).value();
        } else {
            if (operands.size() < 2) { organism.instructionFailed("Invalid operands for POKE/POKI."); return; }
            valueToWrite = operands.get(0).value();
            vector = (int[]) operands.get(1).value();
        }

        if (this.targetCoordinate == null) {
            this.targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);
        }

        if (getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
            if (valueToWrite instanceof int[]) {
                organism.instructionFailed("POKE: Cannot write vectors to the world.");
                return;
            }
            Molecule toWriteRaw = org.evochora.runtime.model.Molecule.fromInt((Integer) valueToWrite);
            // CODE:0 should always have marker=0 (represents empty cell)
            int marker = (toWriteRaw.type() == Config.TYPE_CODE && toWriteRaw.value() == 0) ? 0 : organism.getMr();
            Molecule toWrite = new Molecule(toWriteRaw.type(), toWriteRaw.value(), marker);

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
        int targetReg;
        int[] vector;

        if (getName().endsWith("S")) {
            if (operands.size() != 1) { organism.instructionFailed("Invalid operands for " + getName()); return; }
            vector = (int[]) operands.get(0).value();
            targetReg = -1;
        } else {
            if (operands.size() != 2) { organism.instructionFailed("Invalid operands for " + getName()); return; }
            targetReg = operands.get(0).rawSourceId();
            vector = (int[]) operands.get(1).value();
        }

        if (this.targetCoordinate == null) {
            this.targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);
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
        } else {
            organism.getDataStack().push(valueToStore);
        }

        environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), targetCoordinate);
        environment.clearOwner(targetCoordinate);
    }

    private void handlePeekPoke(ExecutionContext context) {
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();
        List<Operand> operands = resolveOperands(environment);
        int targetReg;
        Object valueToWrite;
        int[] vector;

        if ("PPKS".equals(getName())) {
            if (operands.size() < 2) { organism.instructionFailed("Invalid operands for PPKS."); return; }
            targetReg = -1; // Stack operation
            valueToWrite = operands.get(0).value();
            vector = (int[]) operands.get(1).value();
        } else if ("PPKI".equals(getName())) {
            if (operands.size() < 2) { organism.instructionFailed("Invalid operands for PPKI."); return; }
            targetReg = operands.get(0).rawSourceId();
            valueToWrite = operands.get(0).value(); // Same register for read/write
            vector = (int[]) operands.get(1).value(); // Read vector from operands
        } else {
            if (operands.size() < 2) { organism.instructionFailed("Invalid operands for PPKR."); return; }
            targetReg = operands.get(0).rawSourceId();
            valueToWrite = operands.get(0).value(); // Same register for read/write
            vector = (int[]) operands.get(1).value();
        }


        if (this.targetCoordinate == null) {
            this.targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);
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
            } else {
                organism.getDataStack().push(valueToStore);
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
            Molecule toWriteRaw = org.evochora.runtime.model.Molecule.fromInt((Integer) valueToWrite);
            // CODE:0 should always have marker=0 (represents empty cell)
            int marker = (toWriteRaw.type() == Config.TYPE_CODE && toWriteRaw.value() == 0) ? 0 : organism.getMr();
            Molecule toWrite = new Molecule(toWriteRaw.type(), toWriteRaw.value(), marker);

            // Energy costs and entropy dissipation are now handled by the thermodynamic policy in VirtualMachine

            // Write the new value (cell is now empty, so this should always succeed)
            // CODE:0 should always have owner=0 (represents empty cell)
            int ownerId = (toWrite.type() == Config.TYPE_CODE && toWrite.toScalarValue() == 0) ? 0 : organism.getId();
            environment.setMolecule(toWrite, ownerId, targetCoordinate);
        }
    }


    /**
     * Returns the target coordinates for conflict resolution.
     * <p>
     * <b>Caching Behavior:</b> The target coordinate is computed on first call and cached
     * in {@code this.targetCoordinate}. Subsequent calls return the cached value without
     * re-reading operands. This is a performance optimization for conflict resolution
     * which may call this method multiple times.
     * <p>
     * <b>Invocation Order Dependency:</b> This method reads from the cached operands
     * (populated by {@code resolveOperands()} in the Plan phase). If an
     * {@link org.evochora.runtime.spi.IInstructionInterceptor} modifies operands,
     * it must do so BEFORE this method is called. The current tick cycle guarantees this:
     * <ol>
     *   <li>Plan phase: {@code vm.plan()} calls {@code resolveOperands()}</li>
     *   <li>Interception: Interceptors may modify operands</li>
     *   <li>Conflict resolution: {@code resolveConflicts()} calls this method (first call, caches result)</li>
     *   <li>Execute phase: Instruction executes with final operand values</li>
     * </ol>
     * <p>
     * <b>Warning:</b> Do not call this method before interceptors have run, as operand
     * modifications after caching will not affect the target coordinate.
     *
     * @return List containing the single target coordinate, or empty list if invalid
     */
    @Override
    public List<int[]> getTargetCoordinates() {
        if (this.targetCoordinate != null) {
            return List.of(this.targetCoordinate);
        }

        // Use operands resolved during Plan phase (resolveOperands is idempotent)
        Environment environment = organism.getSimulation().getEnvironment();
        List<Operand> operands = resolveOperands(environment);
        if (operands.isEmpty()) {
            return List.of();
        }

        // Find vector: the last operand that is an int[] (convention for all variants)
        int[] vector = null;
        for (int i = operands.size() - 1; i >= 0; i--) {
            Object value = operands.get(i).value();
            if (value instanceof int[]) {
                vector = (int[]) value;
                break;
            }
        }

        if (vector == null || !organism.isUnitVector(vector)) {
            return List.of();
        }

        this.targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);
        return List.of(this.targetCoordinate);
    }
}
package org.evochora.runtime.isa.instructions;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.IEnvironmentModifyingInstruction;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public class EnvironmentInteractionInstruction extends Instruction implements IEnvironmentModifyingInstruction {

    private int[] targetCoordinate;

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
            } else {
                organism.instructionFailed("Unknown world interaction instruction: " + opName);
            }

        } catch (NoSuchElementException e) {
            organism.instructionFailed("Invalid operands for " + getName());
        } catch (ClassCastException | ArrayIndexOutOfBoundsException e) {
            organism.instructionFailed("Invalid operand types for world interaction.");
        }
    }

    @Override
    public int getCost(Organism organism, Environment environment, java.util.List<Integer> rawArguments) {
        // Base cost 1 for env interactions only if the instruction will actually execute (winner or no conflict)
        if (getConflictStatus() == ConflictResolutionStatus.LOST_TARGET_OCCUPIED
                || getConflictStatus() == ConflictResolutionStatus.LOST_TARGET_EMPTY
                || getConflictStatus() == ConflictResolutionStatus.LOST_OTHER_REASON
                || getConflictStatus() == ConflictResolutionStatus.LOST_LOWER_ID_WON) {
            return 0;
        }
        return 1;
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
            valueToWrite = readOperand(operands.get(0).rawSourceId());
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
            Molecule toWrite = org.evochora.runtime.model.Molecule.fromInt((Integer) valueToWrite);
            int additionalCost = 0;
            if (toWrite.type() == Config.TYPE_ENERGY || toWrite.type() == Config.TYPE_STRUCTURE) {
                additionalCost = Math.abs(toWrite.toScalarValue());
            } else if (toWrite.type() == Config.TYPE_CODE || toWrite.type() == Config.TYPE_DATA) {
                additionalCost = 5;
            }
            if (additionalCost > 0) organism.takeEr(additionalCost);

            if (environment.getMolecule(targetCoordinate).isEmpty()) {
                environment.setMolecule(toWrite, organism.getId(), targetCoordinate);
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

        Object valueToStore;
        if (s.type() == Config.TYPE_ENERGY) {
            int energyToTake = Math.min(s.toScalarValue(), Config.MAX_ORGANISM_ENERGY - organism.getEr());
            organism.addEr(energyToTake);
            valueToStore = new Molecule(Config.TYPE_ENERGY, energyToTake).toInt();
        } else {
            int ownerId = environment.getOwnerId(targetCoordinate);
            if (s.type() == Config.TYPE_STRUCTURE) {
                if (!organism.isCellAccessible(ownerId)) {
                    int cost = Math.abs(s.toScalarValue());
                    if (cost > 0) organism.takeEr(cost);
                }
            } else if (s.type() == Config.TYPE_CODE || s.type() == Config.TYPE_DATA) {
                // Treat ownerId==0 as foreign/neutral for cost purposes
                if (!(ownerId == organism.getId() && ownerId != 0)) {
                    organism.takeEr(5);
                }
            }
            valueToStore = s.toInt();
        }

        if (targetReg != -1) {
            writeOperand(targetReg, valueToStore);
        } else {
            organism.getDataStack().push(valueToStore);
        }

        environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), targetCoordinate);
        environment.clearOwner(targetCoordinate);
    }

    @Override
    public List<int[]> getTargetCoordinates() {
        if (this.targetCoordinate != null) {
            return List.of(this.targetCoordinate);
        }

        try {
            Environment environment = organism.getSimulation().getEnvironment();
            String opName = getName();
            int[] vector = null;

            int[] currentIp = organism.getIpBeforeFetch();

            if (opName.endsWith("S")) {
                if (organism.getDataStack().size() >= (opName.equals("POKS") ? 2 : 1)) {
                    Iterator<Object> it = organism.getDataStack().iterator();
                    if (opName.equals("POKS")) it.next();
                    Object vecObj = it.next();
                    if (vecObj instanceof int[]) {
                        vector = (int[]) vecObj;
                    }
                }
            } else if (opName.endsWith("I")) {
                int[] vec = new int[Config.WORLD_DIMENSIONS];
                int[] ip = organism.getNextInstructionPosition(currentIp, organism.getDvBeforeFetch(), environment); // CORRECTED
                for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                    Organism.FetchResult res = organism.fetchSignedArgument(ip, environment);
                    vec[i] = res.value();
                    ip = res.nextIp();
                }
                vector = vec;
            } else {
                int[] ip = organism.getNextInstructionPosition(currentIp, organism.getDvBeforeFetch(), environment); // CORRECTED
                Organism.FetchResult vecRegArg = organism.fetchArgument(ip, environment);
                int vecRegId = org.evochora.runtime.model.Molecule.fromInt(vecRegArg.value()).toScalarValue();
                Object vecObj = readOperand(vecRegId);
                if (vecObj instanceof int[]) {
                    vector = (int[]) vecObj;
                }
            }

            if (vector != null) {
                this.targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);
                return List.of(this.targetCoordinate);
            }

        } catch (Exception e) {
            return List.of();
        }
        return List.of();
    }
}
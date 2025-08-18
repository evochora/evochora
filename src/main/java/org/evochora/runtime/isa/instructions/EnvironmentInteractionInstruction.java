package org.evochora.runtime.isa.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.IEnvironmentModifyingInstruction;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class EnvironmentInteractionInstruction extends Instruction implements IEnvironmentModifyingInstruction {

    private int[] targetCoordinate;

    public EnvironmentInteractionInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        try {
            String opName = getName();

            // KORREKTUR: Die Prüfung wurde von startsWith auf eine exakte Überprüfung aller
            // Varianten umgestellt. Das behebt den Fehler.
            if ("POKE".equals(opName) || "POKI".equals(opName) || "POKS".equals(opName)) {
                handlePoke(simulation);
            } else if ("PEEK".equals(opName) || "PEKI".equals(opName) || "PEKS".equals(opName)) {
                handlePeek(simulation);
            } else {
                organism.instructionFailed("Unknown world interaction instruction: " + opName);
            }

        } catch (NoSuchElementException e) {
            organism.instructionFailed("Invalid operands for " + getName());
        } catch (ClassCastException | ArrayIndexOutOfBoundsException e) {
            organism.instructionFailed("Invalid operand types for world interaction.");
        }
    }

    private void handlePoke(Simulation simulation) {
        List<Operand> operands = resolveOperands(simulation.getEnvironment());
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
            this.targetCoordinate = organism.getTargetCoordinate(organism.getDp(), vector, simulation.getEnvironment());
        }

        if (getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
            if (valueToWrite instanceof int[]) {
                organism.instructionFailed("POKE: Cannot write vectors to the world.");
                return;
            }
            Molecule toWrite = org.evochora.runtime.model.Molecule.fromInt((Integer) valueToWrite);
            int cost = Math.abs(toWrite.toScalarValue());
            if (cost > 0) organism.takeEr(cost);

            if (simulation.getEnvironment().getMolecule(targetCoordinate).isEmpty()) {
                simulation.getEnvironment().setMolecule(toWrite, targetCoordinate);
            } else {
                organism.instructionFailed("POKE: Target cell is not empty.");
                if (getConflictStatus() != ConflictResolutionStatus.NOT_APPLICABLE) setConflictStatus(ConflictResolutionStatus.LOST_TARGET_OCCUPIED);
            }
        }
    }

    private void handlePeek(Simulation simulation) {
        List<Operand> operands = resolveOperands(simulation.getEnvironment());
        Environment environment = simulation.getEnvironment();
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
            this.targetCoordinate = organism.getTargetCoordinate(organism.getDp(), vector, environment);
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
            if (s.type() == Config.TYPE_STRUCTURE) {
                int ownerId = environment.getOwnerId(targetCoordinate);
                if (ownerId != organism.getId()) {
                    int cost = Math.abs(s.toScalarValue());
                    if (cost > 0) organism.takeEr(cost);
                }
            } else {
                int cost = Math.abs(s.toScalarValue());
                if (cost > 0) organism.takeEr(cost);
            }
            valueToStore = s.toInt();
        }

        if (targetReg != -1) {
            writeOperand(targetReg, valueToStore);
        } else {
            organism.getDataStack().push(valueToStore);
        }

        environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), targetCoordinate);
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

            if (opName.endsWith("S")) { // POKS, PEKS
                if (organism.getDataStack().size() >= (opName.equals("POKS") ? 2 : 1)) {
                    Iterator<Object> it = organism.getDataStack().iterator();
                    if (opName.equals("POKS")) it.next();
                    Object vecObj = it.next();
                    if (vecObj instanceof int[]) {
                        vector = (int[]) vecObj;
                    }
                }
            } else if (opName.endsWith("I")) { // POKI, PEKI
                int[] vec = new int[Config.WORLD_DIMENSIONS];
                int[] ip = organism.getNextInstructionPosition(currentIp, environment, organism.getDvBeforeFetch());
                for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                    Organism.FetchResult res = organism.fetchSignedArgument(ip, environment);
                    vec[i] = res.value();
                    ip = res.nextIp();
                }
                vector = vec;
            } else { // POKE, PEEK
                int[] ip = organism.getNextInstructionPosition(currentIp, environment, organism.getDvBeforeFetch());
                Organism.FetchResult vecRegArg = organism.fetchArgument(ip, environment);
                int vecRegId = org.evochora.runtime.model.Molecule.fromInt(vecRegArg.value()).toScalarValue();
                Object vecObj = readOperand(vecRegId);
                if (vecObj instanceof int[]) {
                    vector = (int[]) vecObj;
                }
            }

            if (vector != null) {
                this.targetCoordinate = organism.getTargetCoordinate(organism.getDp(), vector, environment);
                return List.of(this.targetCoordinate);
            }

        } catch (Exception e) {
            return List.of();
        }
        return List.of();
    }
}
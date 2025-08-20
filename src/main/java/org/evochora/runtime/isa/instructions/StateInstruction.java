package org.evochora.runtime.isa.instructions;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;

import java.util.List;
import java.util.Random;

public class StateInstruction extends Instruction {

    public StateInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context, ProgramArtifact artifact) {
        Organism organism = context.getOrganism();
        String opName = getName();
        List<Operand> operands = resolveOperands(context.getWorld());

        try {
            switch (opName) {
                case "TURN":
                    handleTurn(operands);
                    break;
                case "TRNI":
                    handleTrni(operands);
                    break;
                case "TRNS":
                    handleTrns();
                    break;
                case "SYNC":
                    handleSync();
                    break;
                case "NRG":
                case "NRGS":
                    handleNrg(opName, operands);
                    break;
                case "FORK":
                    handleFork(operands, organism.getSimulation());
                    break;
                case "DIFF":
                    handleDiff(operands);
                    break;
                case "DIFS":
                    handleDifs();
                    break;
                case "POS":
                    handlePos(operands);
                    break;
                case "POSS":
                    handlePoss();
                    break;
                case "RAND":
                    handleRand(operands);
                    break;
                case "RNDS":
                    handleRnds();
                    break;
                case "ADPR":
                case "ADPI":
                case "ADPS":
                    handleActiveDp(opName, operands);
                    break;
                case "SEEK":
                case "SEKI":
                case "SEKS":
                    handleSeek(operands, context.getWorld());
                    break;
                case "FRKI":
                case "FRKS":
                    handleForkExtended(opName, operands, context.getWorld(), organism.getSimulation());
                    break;
                case "SCAN":
                case "SCNI":
                case "SCNS":
                    handleScan(opName, operands, context.getWorld());
                    break;
                default:
                    organism.instructionFailed("Unknown state instruction: " + opName);
            }
        } catch (ClassCastException | ArrayIndexOutOfBoundsException e) {
            organism.instructionFailed("Invalid operand types for state instruction.");
        }
    }

    private void handleTurn(List<Operand> operands) {
        if (operands.size() != 1) { organism.instructionFailed("Invalid operands for TURN."); return; }
        int[] newDv = (int[]) operands.get(0).value();
        if (organism.isUnitVector(newDv)) {
            organism.setDv(newDv);
        }
    }

    private void handleSync() {
        organism.setActiveDp(organism.getIpBeforeFetch());
    }

    private void handleNrg(String opName, List<Operand> operands) {
        if ("NRGS".equals(opName)) {
            organism.getDataStack().push(new Molecule(Config.TYPE_DATA, organism.getEr()).toInt());
        } else {
            if (operands.size() != 1) { organism.instructionFailed("Invalid operands for NRG."); return; }
            int targetReg = operands.get(0).rawSourceId();
            writeOperand(targetReg, new Molecule(Config.TYPE_DATA, organism.getEr()).toInt());
        }
    }

    private void handleFork(List<Operand> operands, Simulation simulation) {
        if (operands.size() != 3) { organism.instructionFailed("Invalid operands for FORK."); return; }
        int[] delta = (int[]) operands.get(0).value();
        int energy = org.evochora.runtime.model.Molecule.fromInt((Integer) operands.get(1).value()).toScalarValue();
        int[] childDv = (int[]) operands.get(2).value();
        int totalCost = getCost(organism, simulation.getEnvironment(), null);
        if (energy > 0 && organism.getEr() >= totalCost) {
            int[] childIp = organism.getTargetCoordinate(organism.getActiveDp(), delta, simulation.getEnvironment());
            organism.takeEr(totalCost);
            Organism child = Organism.create(simulation, childIp, energy, organism.getLogger());
            child.setDv(childDv);
            child.setParentId(organism.getId());
            child.setBirthTick(simulation.getCurrentTick());
            simulation.addNewOrganism(child);
        } else {
            organism.instructionFailed("FORK failed due to insufficient energy or invalid parameters.");
        }
    }

    private void handleDiff(List<Operand> operands) {
        if (operands.size() != 1) { organism.instructionFailed("Invalid operands for DIFF."); return; }
        int[] ip = organism.getIp();
        int[] dp = organism.getActiveDp();
        int[] delta = new int[ip.length];
        for (int i = 0; i < ip.length; i++) {
            delta[i] = dp[i] - ip[i];
        }
        writeOperand(operands.get(0).rawSourceId(), delta);
    }

    private void handlePos(List<Operand> operands) {
        if (operands.size() != 1) { organism.instructionFailed("Invalid operands for POS."); return; }
        int[] currentIp = organism.getIp();
        int[] initialPosition = organism.getInitialPosition();
        int[] delta = new int[currentIp.length];
        for (int i = 0; i < currentIp.length; i++) {
            delta[i] = currentIp[i] - initialPosition[i];
        }
        writeOperand(operands.get(0).rawSourceId(), delta);
    }

    private void handleRand(List<Operand> operands) {
        if (operands.size() != 1) { organism.instructionFailed("Invalid operands for RAND."); return; }
        Operand op = operands.get(0);
        Molecule s = org.evochora.runtime.model.Molecule.fromInt((Integer)op.value());
        int upperBound = s.toScalarValue();
        if (upperBound <= 0) {
            organism.instructionFailed("RAND upper bound must be > 0.");
            writeOperand(op.rawSourceId(), new Molecule(s.type(), 0).toInt());
            return;
        }
        Random random = organism.getRandom();
        int randomValue = random.nextInt(upperBound);
        writeOperand(op.rawSourceId(), new Molecule(s.type(), randomValue).toInt());
    }

    private void handleRnds() {
        if (organism.getDataStack().isEmpty()) { organism.instructionFailed("RNDS requires N on stack."); return; }
        Object nObj = organism.getDataStack().pop();
        if (!(nObj instanceof Integer ni)) { organism.instructionFailed("RNDS requires scalar on stack."); return; }
        int upperBound = org.evochora.runtime.model.Molecule.fromInt(ni).toScalarValue();
        if (upperBound <= 0) { organism.instructionFailed("RNDS upper bound must be > 0."); organism.getDataStack().push(new Molecule(Config.TYPE_DATA, 0).toInt()); return; }
        int v = organism.getRandom().nextInt(upperBound);
        organism.getDataStack().push(new Molecule(Config.TYPE_DATA, v).toInt());
    }

    private void handleSeek(List<Operand> operands, Environment environment) {
        if (operands.size() != 1) {
            organism.instructionFailed("Invalid operands for SEEK variant.");
            return;
        }
        int[] vector = (int[]) operands.get(0).value();
        int[] targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);

        Molecule moleculeAtTarget = environment.getMolecule(targetCoordinate);
        int ownerIdAtTarget = environment.getOwnerId(targetCoordinate[0], targetCoordinate[1]);
        boolean ownedBySelf = ownerIdAtTarget == organism.getId();

        if (moleculeAtTarget.isEmpty() || ownedBySelf) {
            organism.setActiveDp(targetCoordinate);
        } else {
            organism.instructionFailed("SEEK: Target cell is owned by another organism.");
        }
    }

    private void handleForkExtended(String opName, List<Operand> operands, Environment environment, Simulation simulation) {
        if ("FRKI".equals(opName)) {
            if (operands.size() != 3) { organism.instructionFailed("FRKI expects <Vec>, <Lit>, <Vec>."); return; }
            int[] delta = (int[]) operands.get(0).value();
            int energy = org.evochora.runtime.model.Molecule.fromInt((Integer) operands.get(1).value()).toScalarValue();
            int[] childDv = (int[]) operands.get(2).value();
            int totalCost = getCost(organism, environment, null);
            if (energy > 0 && organism.getEr() >= totalCost) {
                int[] childIp = organism.getTargetCoordinate(organism.getActiveDp(), delta, environment);
                organism.takeEr(totalCost);
                Organism child = Organism.create(simulation, childIp, energy, organism.getLogger());
                child.setDv(childDv);
                child.setParentId(organism.getId());
                child.setBirthTick(simulation.getCurrentTick());
                simulation.addNewOrganism(child);
            } else {
                organism.instructionFailed("FRKI failed due to insufficient energy or invalid parameters.");
            }
        } else {
            // FRKS: stack variant expects [childDv, energy, delta] on stack (top-down)
            if (organism.getDataStack().size() < 3) { organism.instructionFailed("FRKS requires 3 values on stack."); return; }
            Object dvObj = organism.getDataStack().pop();
            Object energyObj = organism.getDataStack().pop();
            Object deltaObj = organism.getDataStack().pop();
            if (!(dvObj instanceof int[] childDv) || !(energyObj instanceof Integer ei) || !(deltaObj instanceof int[] delta)) {
                organism.instructionFailed("FRKS stack contents invalid.");
                return;
            }
            int energy = org.evochora.runtime.model.Molecule.fromInt(ei).toScalarValue();
            int totalCost = getCost(organism, environment, null);
            if (energy > 0 && organism.getEr() >= totalCost) {
                int[] childIp = organism.getTargetCoordinate(organism.getActiveDp(), delta, environment);
                organism.takeEr(totalCost);
                Organism child = Organism.create(simulation, childIp, energy, organism.getLogger());
                child.setDv(childDv);
                child.setParentId(organism.getId());
                child.setBirthTick(simulation.getCurrentTick());
                simulation.addNewOrganism(child);
            } else {
                organism.instructionFailed("FRKS failed due to insufficient energy or invalid parameters.");
            }
        }
    }

    private void handleScan(String opName, List<Operand> operands, Environment environment) {
        int targetReg;
        int[] vector;
        if (opName.endsWith("S")) {
            if (operands.size() != 1) { organism.instructionFailed("Invalid operands for " + opName); return; }
            vector = (int[]) operands.get(0).value();
            targetReg = -1;
        } else {
            if (operands.size() != 2) { organism.instructionFailed("Invalid operands for " + opName); return; }
            targetReg = operands.get(0).rawSourceId();
            vector = (int[]) operands.get(1).value();
        }
        int[] target = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);
        Molecule s = environment.getMolecule(target);
        if (opName.endsWith("S")) {
            organism.getDataStack().push(s.toInt());
        } else {
            writeOperand(targetReg, s.toInt());
        }
    }

    private void handleTrni(List<Operand> operands) {
        if (operands.size() != 1) { organism.instructionFailed("Invalid operands for TRNI."); return; }
        int[] newDv = (int[]) operands.get(0).value();
        if (organism.isUnitVector(newDv)) {
            organism.setDv(newDv);
        }
    }

    private void handleTrns() {
        if (organism.getDataStack().isEmpty()) { organism.instructionFailed("TRNS requires vector on stack."); return; }
        Object top = organism.getDataStack().pop();
        if (!(top instanceof int[] vec)) { organism.instructionFailed("TRNS requires vector on stack."); return; }
        if (organism.isUnitVector(vec)) {
            organism.setDv(vec);
        }
    }

    private void handlePoss() {
        int[] currentIp = organism.getIp();
        int[] initialPosition = organism.getInitialPosition();
        int[] delta = new int[currentIp.length];
        for (int i = 0; i < currentIp.length; i++) {
            delta[i] = currentIp[i] - initialPosition[i];
        }
        organism.getDataStack().push(delta);
    }

    private void handleDifs() {
        int[] ip = organism.getIp();
        int[] dp = organism.getActiveDp();
        int[] delta = new int[ip.length];
        for (int i = 0; i < ip.length; i++) {
            delta[i] = dp[i] - ip[i];
        }
        organism.getDataStack().push(delta);
    }

    private void handleActiveDp(String opName, List<Operand> operands) {
        switch (opName) {
            case "ADPR": {
                if (operands.size() != 1) { organism.instructionFailed("ADPR expects one register operand."); return; }
                Object raw = operands.get(0).value();
                if (!(raw instanceof Integer i)) { organism.instructionFailed("ADPR register must hold scalar."); return; }
                int idx = Molecule.fromInt(i).toScalarValue();
                organism.setActiveDpIndex(idx);
                break;
            }
            case "ADPI": {
                if (operands.size() != 1) { organism.instructionFailed("ADPI expects one immediate operand."); return; }
                int idx = Molecule.fromInt((Integer) operands.get(0).value()).toScalarValue();
                organism.setActiveDpIndex(idx);
                break;
            }
            case "ADPS": {
                if (organism.getDataStack().isEmpty()) { organism.instructionFailed("ADPS requires index on stack."); return; }
                Object raw = organism.getDataStack().pop();
                if (!(raw instanceof Integer i)) { organism.instructionFailed("ADPS requires scalar on stack."); return; }
                int idx = Molecule.fromInt(i).toScalarValue();
                organism.setActiveDpIndex(idx);
                break;
            }
        }
    }

    @Override
    public int getCost(Organism organism, Environment environment, List<Integer> rawArguments) {
        String name = getName();
        if (name.equals("FORK")) {
            List<Operand> operands = resolveOperands(environment);
            if (operands.size() == 3 && operands.get(1).value() instanceof Integer) {
                return 10 + org.evochora.runtime.model.Molecule.fromInt((Integer)operands.get(1).value()).toScalarValue();
            }
            return 10;
        }
        // New state ops have base cost 1; return default (1) via super
        return super.getCost(organism, environment, rawArguments);
    }
}
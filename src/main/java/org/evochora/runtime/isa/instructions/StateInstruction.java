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

/**
 * Handles a wide variety of state-related instructions, such as TURN, SYNC, NRG, FORK,
 * DIFF, POS, RAND, SEEK, and various scanning instructions.
 */
public class StateInstruction extends Instruction {

    /**
     * Constructs a new StateInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
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
                case "RBIR":
                case "RBII":
                case "RBIS":
                    handleRbit(opName, operands);
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
                case "SPNR":
                case "SPNS":
                    handleScanPassableNeighbors(opName, operands, context.getWorld());
                    break;
                case "SNTR":
                case "SNTI":
                case "SNTS":
                    handleScanNeighborsByType(opName, operands, context.getWorld());
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
        if (!organism.isUnitVector(delta)) {
            return;
        }
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
        if (upperBound <= 0) { organism.instructionFailed("RNDS upper bound must be > 0."); return; }
        int v = organism.getRandom().nextInt(upperBound);
        organism.getDataStack().push(new Molecule(Config.TYPE_DATA, v).toInt());
    }

    private void handleRbit(String opName, List<Operand> operands) {
        // RBIR %DEST_REG, %SOURCE_REG
        // RBII %DEST_REG, <MASK_LITERAL>
        // RBIS (stack): pop mask, push single-bit mask
        final int width = Config.VALUE_BITS;
        final int maskAll = (1 << width) - 1;

        if ("RBIS".equals(opName)) {
            if (organism.getDataStack().isEmpty()) { organism.instructionFailed("RBIS requires a source mask on the stack."); return; }
            Object srcObj = organism.getDataStack().pop();
            if (!(srcObj instanceof Integer)) { organism.instructionFailed("RBIS requires a scalar mask on the stack."); return; }
            Molecule srcMol = org.evochora.runtime.model.Molecule.fromInt((Integer) srcObj);
            int srcMask = srcMol.toScalarValue() & maskAll;
            int resultMask = chooseRandomSetBitMask(srcMask);
            organism.getDataStack().push(new Molecule(srcMol.type(), resultMask).toInt());
            return;
        }

        if (operands.size() != 2) { organism.instructionFailed(opName + " requires two operands."); return; }
        Operand dest = operands.get(0);
        Operand src = operands.get(1);
        if (!(dest.value() instanceof Integer)) { organism.instructionFailed(opName + " destination must be a scalar register."); return; }

        Molecule srcMol;
        if ("RBII".equals(opName)) {
            // immediate uses DATA type
            if (!(src.value() instanceof Integer)) { organism.instructionFailed("RBII requires immediate scalar mask."); return; }
            srcMol = new Molecule(Config.TYPE_DATA, (Integer) src.value());
        } else {
            if (!(src.value() instanceof Integer)) { organism.instructionFailed("RBIR requires scalar source register."); return; }
            srcMol = org.evochora.runtime.model.Molecule.fromInt((Integer) src.value());
        }

        int srcMask = srcMol.toScalarValue() & maskAll;
        int resultMask = chooseRandomSetBitMask(srcMask);
        if (dest.rawSourceId() != -1) {
            writeOperand(dest.rawSourceId(), new Molecule(srcMol.type(), resultMask).toInt());
        } else {
            organism.instructionFailed(opName + " destination must be a register.");
        }
    }

    private int chooseRandomSetBitMask(int mask) {
        if (mask == 0) return 0;
        // collect indices of set bits within VALUE_BITS
        int[] indices = new int[Config.VALUE_BITS];
        int count = 0;
        for (int i = 0; i < Config.VALUE_BITS; i++) {
            if (((mask >>> i) & 1) != 0) {
                indices[count++] = i;
            }
        }
        if (count == 0) return 0;
        int pick = organism.getRandom().nextInt(count);
        int bitIndex = indices[pick];
        int result = (1 << bitIndex) & ((1 << Config.VALUE_BITS) - 1);
        return result;
    }

    private void handleSeek(List<Operand> operands, Environment environment) {
        if (operands.size() != 1) {
            organism.instructionFailed("Invalid operands for SEEK variant.");
            return;
        }
        int[] vector = (int[]) operands.get(0).value();
        if (!organism.isUnitVector(vector)) {
            return;
        }
        int[] targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);

        Molecule moleculeAtTarget = environment.getMolecule(targetCoordinate);
        int ownerIdAtTarget = environment.getOwnerId(targetCoordinate[0], targetCoordinate[1]);
        if (moleculeAtTarget.isEmpty() || organism.isCellAccessible(ownerIdAtTarget)) {
            organism.setActiveDp(targetCoordinate);
        } else {
            organism.instructionFailed("SEEK: Target cell is owned by another organism.");
        }
    }

    private void handleForkExtended(String opName, List<Operand> operands, Environment environment, Simulation simulation) {
        if ("FRKI".equals(opName)) {
            if (operands.size() != 3) { organism.instructionFailed("FRKI expects <Vec>, <Lit>, <Vec>."); return; }
            int[] delta = (int[]) operands.get(0).value();
            if (!organism.isUnitVector(delta)) {
                return;
            }
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
            if (!organism.isUnitVector(delta)) {
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
        if (!organism.isUnitVector(vector)) {
            return;
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

    private void handleScanPassableNeighbors(String opName, List<Operand> operands, Environment environment) {
        int dims = environment.getShape().length;
        int scanDims = Math.min(dims, 8);
        int[] dp = organism.getActiveDp();
        int mask = 0;
        for (int d = 0; d < scanDims; d++) {
            // + direction
            int[] vecPlus = new int[dims];
            vecPlus[d] = 1;
            int[] tgtPlus = organism.getTargetCoordinate(dp, vecPlus, environment);
            org.evochora.runtime.model.Molecule mPlus = environment.getMolecule(tgtPlus);
            int ownerPlus = environment.getOwnerId(tgtPlus);
            boolean passablePlus = mPlus.isEmpty() || organism.isCellAccessible(ownerPlus);
            if (passablePlus) {
                mask |= (1 << (2 * d));
            }

            // - direction
            int[] vecMinus = new int[dims];
            vecMinus[d] = -1;
            int[] tgtMinus = organism.getTargetCoordinate(dp, vecMinus, environment);
            org.evochora.runtime.model.Molecule mMinus = environment.getMolecule(tgtMinus);
            int ownerMinus = environment.getOwnerId(tgtMinus);
            boolean passableMinus = mMinus.isEmpty() || organism.isCellAccessible(ownerMinus);
            if (passableMinus) {
                mask |= (1 << (2 * d + 1));
            }
        }

        if ("SPNS".equals(opName)) {
            organism.getDataStack().push(new Molecule(Config.TYPE_DATA, mask).toInt());
        } else {
            if (operands.size() != 1) { organism.instructionFailed("SPNR requires one destination register."); return; }
            int dest = operands.get(0).rawSourceId();
            writeOperand(dest, new Molecule(Config.TYPE_DATA, mask).toInt());
        }
    }

    private void handleScanNeighborsByType(String opName, List<Operand> operands, Environment environment) {
        // Determine destination (register or stack) and the requested type
        boolean toStack = opName.endsWith("S");
        int destReg = -1;
        int requestedType;

        if ("SNTR".equals(opName)) {
            if (operands.size() != 2) { organism.instructionFailed("SNTR expects %DEST_REG, %TYPE_REG"); return; }
            destReg = operands.get(0).rawSourceId();
            Object typeObj = operands.get(1).value();
            if (!(typeObj instanceof Integer ti)) { organism.instructionFailed("SNTR type source must be scalar."); return; }
            requestedType = Molecule.fromInt(ti).type();
        } else if ("SNTI".equals(opName)) {
            if (operands.size() != 2) { organism.instructionFailed("SNTI expects %DEST_REG, <Type_Lit>"); return; }
            destReg = operands.get(0).rawSourceId();
            Object imm = operands.get(1).value();
            if (!(imm instanceof Integer ii)) { organism.instructionFailed("SNTI immediate must be scalar."); return; }
            requestedType = Molecule.fromInt(ii).type();
        } else { // SNTS
            if (operands.size() != 1) { organism.instructionFailed("SNTS expects <Type> on stack"); return; }
            Object top = operands.get(0).value();
            if (!(top instanceof Integer si)) { organism.instructionFailed("SNTS requires scalar type on stack."); return; }
            requestedType = Molecule.fromInt(si).type();
            toStack = true;
        }

        int dims = environment.getShape().length;
        int scanDims = Math.min(dims, 8);
        int[] dp = organism.getActiveDp();
        int mask = 0;
        for (int d = 0; d < scanDims; d++) {
            int[] vecPlus = new int[dims];
            vecPlus[d] = 1;
            int[] tgtPlus = organism.getTargetCoordinate(dp, vecPlus, environment);
            Molecule mPlus = environment.getMolecule(tgtPlus);
            if (mPlus.type() == requestedType) {
                mask |= (1 << (2 * d));
            }

            int[] vecMinus = new int[dims];
            vecMinus[d] = -1;
            int[] tgtMinus = organism.getTargetCoordinate(dp, vecMinus, environment);
            Molecule mMinus = environment.getMolecule(tgtMinus);
            if (mMinus.type() == requestedType) {
                mask |= (1 << (2 * d + 1));
            }
        }

        if (toStack) {
            organism.getDataStack().push(new Molecule(Config.TYPE_DATA, mask).toInt());
        } else {
            writeOperand(destReg, new Molecule(Config.TYPE_DATA, mask).toInt());
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
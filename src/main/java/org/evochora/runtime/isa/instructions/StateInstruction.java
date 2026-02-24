package org.evochora.runtime.isa.instructions;

import java.util.List;
import java.util.Random;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Variant;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import static org.evochora.runtime.isa.Instruction.OperandSource.*;

/**
 * Handles a wide variety of state-related instructions, such as TURN, SYNC, NRG, FORK,
 * DIFF, POS, RAND, SEEK, and various scanning instructions.
 */
public class StateInstruction extends Instruction {

    private static int family;

    /**
     * Registers all state instructions with the instruction registry.
     * <p>
     * <b>Thread safety:</b> Must only be called during single-threaded initialization ({@link Instruction#init()}).
     *
     * @param f the family ID for this instruction family
     */
    public static void register(int f) {
        family = f;
        // Operation 0: SCAN (scan environment in direction)
        reg(0, Variant.RR, "SCAN", REGISTER, REGISTER);
        reg(0, Variant.RV, "SCNI", REGISTER, VECTOR);
        reg(0, Variant.S, "SCNS", STACK);
        // Operation 1: SEEK (set active data pointer direction)
        reg(1, Variant.R, "SEEK", REGISTER);
        reg(1, Variant.V, "SEKI", VECTOR);
        reg(1, Variant.S, "SEKS", STACK);
        // Operation 2: TURN (turn/rotate direction)
        reg(2, Variant.R, "TURN", REGISTER);
        reg(2, Variant.V, "TRNI", VECTOR);
        reg(2, Variant.S, "TRNS", STACK);
        // Operation 3: SYNC (synchronize/wait)
        reg(3, Variant.NONE, "SYNC");
        // Operation 4: NRG (get energy)
        reg(4, Variant.R, "NRG", REGISTER);
        reg(4, Variant.NONE, "NRGS");
        // Operation 5: NTR (get entropy)
        reg(5, Variant.R, "NTR", REGISTER);
        reg(5, Variant.NONE, "NTRS");
        // Operation 6: DIFF (get difficulty/thermodynamic gradient)
        reg(6, Variant.R, "DIFF", REGISTER);
        reg(6, Variant.NONE, "DIFS");
        // Operation 7: POS (get position)
        reg(7, Variant.R, "POS", REGISTER);
        reg(7, Variant.NONE, "POSS");
        // Operation 8: RAND (random number)
        reg(8, Variant.R, "RAND", REGISTER);
        reg(8, Variant.S, "RNDS", STACK);
        // Operation 9: FORK (replicate organism) — writes to shared environment
        regUnsafe(9, Variant.RRR, "FORK", REGISTER, REGISTER, REGISTER);
        regUnsafe(9, Variant.VIV, "FRKI", VECTOR, IMMEDIATE, VECTOR);
        regUnsafe(9, Variant.SSS, "FRKS", STACK, STACK, STACK);
        // Operation 10: ADP (active data pointer selection)
        reg(10, Variant.R, "ADPR", REGISTER);
        reg(10, Variant.I, "ADPI", IMMEDIATE);
        reg(10, Variant.S, "ADPS", STACK);
        // Operation 11: SPN (scan passable neighbors)
        reg(11, Variant.R, "SPNR", REGISTER);
        reg(11, Variant.NONE, "SPNS");
        // Operation 12: SNT (scan neighbors by type)
        reg(12, Variant.RR, "SNTR", REGISTER, REGISTER);
        reg(12, Variant.RI, "SNTI", REGISTER, IMMEDIATE);
        reg(12, Variant.S, "SNTS", STACK);
        // Operation 13: RBI (random bit from mask)
        reg(13, Variant.RR, "RBIR", REGISTER, REGISTER);
        reg(13, Variant.RI, "RBII", REGISTER, IMMEDIATE);
        reg(13, Variant.S, "RBIS", STACK);
        // Operation 14: GDV (get DV value)
        reg(14, Variant.R, "GDVR", REGISTER);
        reg(14, Variant.NONE, "GDVS");
        // Operation 15: SMR (set molecule marker register)
        reg(15, Variant.R, "SMR", REGISTER);
        reg(15, Variant.I, "SMRI", IMMEDIATE);
        reg(15, Variant.S, "SMRS", STACK);
        // Operation 16: GMR (get molecule marker register)
        reg(16, Variant.R, "GMR", REGISTER);
        reg(16, Variant.NONE, "GMRS");
        // Operation 17: CMR (clear markers - remove own molecules with matching marker) — writes to shared environment
        regUnsafe(17, Variant.R, "CMR", REGISTER);
        regUnsafe(17, Variant.I, "CMRI", IMMEDIATE);
        regUnsafe(17, Variant.S, "CMRS", STACK);
    }

    private static void reg(int op, int variant, String name, OperandSource... sources) {
        Instruction.registerOp(StateInstruction.class, StateInstruction::new, family, op, variant, name, sources);
    }

    private static void regUnsafe(int op, int variant, String name, OperandSource... sources) {
        Instruction.registerOp(StateInstruction.class, StateInstruction::new,
                family, op, variant, name, false, sources);
    }

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
                    handleTrns(operands);
                    break;
                case "SYNC":
                    handleSync();
                    break;
                case "NRG":
                case "NRGS":
                    handleNrg(opName, operands);
                    break;
                case "NTR":
                case "NTRS":
                    handleNtr(opName, operands);
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
                    handleRnds(operands);
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
                case "GDVR":
                case "GDVS":
                    handleGdv(opName, operands);
                    break;
                case "SMR":
                case "SMRI":
                case "SMRS":
                    handleSmr(opName, operands);
                    break;
                case "GMR":
                case "GMRS":
                    handleGmr(opName, operands);
                    break;
                case "CMR":
                case "CMRI":
                case "CMRS":
                    handleCmr(opName, operands, context.getWorld());
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

    private void handleNtr(String opName, List<Operand> operands) {
        if ("NTRS".equals(opName)) {
            organism.getDataStack().push(new Molecule(Config.TYPE_DATA, organism.getSr()).toInt());
        } else {
            if (operands.size() != 1) { organism.instructionFailed("Invalid operands for NTR."); return; }
            int targetReg = operands.get(0).rawSourceId();
            writeOperand(targetReg, new Molecule(Config.TYPE_DATA, organism.getSr()).toInt());
        }
    }

    /**
     * Handles the FORK instruction: spawns a child organism at a neighboring cell.
     * Transfers energy from parent to child and copies marker-matching molecule ownership.
     *
     * @param operands Three operands: unit delta vector, energy scalar, child DV vector.
     * @param simulation The simulation for coordinate resolution and organism registration.
     */
    private void handleFork(List<Operand> operands, Simulation simulation) {
        if (operands.size() != 3) { organism.instructionFailed("Invalid operands for FORK."); return; }
        int[] delta = (int[]) operands.get(0).value();
        if (!organism.isUnitVector(delta)) {
            return;
        }
        int energy = org.evochora.runtime.model.Molecule.fromInt((Integer) operands.get(1).value()).toScalarValue();
        int[] childDv = (int[]) operands.get(2).value();
        // The VirtualMachine already deducted the base cost (10), now we need to deduct the energy given to child
        if (energy > 0 && organism.getEr() >= energy) {
            int[] childIp = organism.getTargetCoordinate(organism.getActiveDp(), delta, simulation.getEnvironment());
            organism.takeEr(energy); // Deduct the energy given to the child
            Organism child = Organism.create(simulation, childIp, energy, organism.getLogger());
            child.setDv(childDv);
            child.setParentId(organism.getId());
            child.setBirthTick(simulation.getCurrentTick());
            child.setProgramId(organism.getProgramId());
            simulation.addNewOrganism(child);

            // Transfer ownership of molecules with matching marker to the child
            int parentMr = organism.getMr();
            simulation.getEnvironment().transferOwnership(organism.getId(), child.getId(), parentMr);
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

    private void handleRnds(List<Operand> operands) {
        // Operand comes from resolveOperands() - no direct stack access needed
        if (operands.isEmpty()) { organism.instructionFailed("RNDS requires N on stack."); return; }
        Object nObj = operands.get(0).value();
        if (!(nObj instanceof Integer ni)) { organism.instructionFailed("RNDS requires scalar on stack."); return; }
        int upperBound = org.evochora.runtime.model.Molecule.fromInt(ni).toScalarValue();
        if (upperBound <= 0) { organism.instructionFailed("RNDS upper bound must be > 0."); return; }
        int v = organism.getRandom().nextInt(upperBound);
        organism.getDataStack().push(new Molecule(Config.TYPE_DATA, v).toInt());
    }

    private void handleRbit(String opName, List<Operand> operands) {
        // RBIR %DEST_REG, %SOURCE_REG
        // RBII %DEST_REG, <MASK_LITERAL>
        // RBIS (stack): read mask from operands, push single-bit mask
        final int width = Config.VALUE_BITS;
        final int maskAll = (1 << width) - 1;

        if ("RBIS".equals(opName)) {
            // Operand comes from resolveOperands() - no direct stack access needed
            if (operands.isEmpty()) { organism.instructionFailed("RBIS requires a source mask on the stack."); return; }
            Object srcObj = operands.get(0).value();
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

        Molecule srcMol;
        if ("RBII".equals(opName)) {
            // immediate: decode stored value to get scalar, then wrap as DATA
            if (!(src.value() instanceof Integer)) { organism.instructionFailed("RBII requires immediate scalar mask."); return; }
            Molecule imm = org.evochora.runtime.model.Molecule.fromInt((Integer) src.value());
            srcMol = new Molecule(Config.TYPE_DATA, imm.toScalarValue());
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
        return (1 << bitIndex) & ((1 << Config.VALUE_BITS) - 1);
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

    /**
     * Handles the extended FORK variants (FRKI / FRKS).
     * FRKI takes immediate operands; FRKS pops delta, energy, and child DV from the data stack.
     * Otherwise identical to {@link #handleFork}: validates the delta as a unit vector,
     * transfers energy, creates a child organism, and transfers marker-matching ownership.
     *
     * @param opName "FRKI" (immediate) or "FRKS" (stack).
     * @param operands Resolved operands for the instruction.
     * @param environment The simulation environment for coordinate resolution.
     * @param simulation The simulation for organism registration.
     */
    private void handleForkExtended(String opName, List<Operand> operands, Environment environment, Simulation simulation) {
        if ("FRKI".equals(opName)) {
            if (operands.size() != 3) { organism.instructionFailed("FRKI expects <Vec>, <Lit>, <Vec>."); return; }
            int[] delta = (int[]) operands.get(0).value();
            if (!organism.isUnitVector(delta)) {
                return;
            }
            int energy = org.evochora.runtime.model.Molecule.fromInt((Integer) operands.get(1).value()).toScalarValue();
            int[] childDv = (int[]) operands.get(2).value();
            // The VirtualMachine already deducted the base cost (1), now we need to deduct the energy given to child
            if (energy > 0 && organism.getEr() >= energy) {
                int[] childIp = organism.getTargetCoordinate(organism.getActiveDp(), delta, environment);
                organism.takeEr(energy); // Deduct the energy given to the child
                Organism child = Organism.create(simulation, childIp, energy, organism.getLogger());
                child.setDv(childDv);
                child.setParentId(organism.getId());
                child.setBirthTick(simulation.getCurrentTick());
                child.setProgramId(organism.getProgramId());
                simulation.addNewOrganism(child);

                // Transfer ownership of molecules with matching marker to the child
                int parentMr = organism.getMr();
                environment.transferOwnership(organism.getId(), child.getId(), parentMr);
            } else {
                organism.instructionFailed("FRKI failed due to insufficient energy or invalid parameters.");
            }
        } else {
            // FRKS: stack variant expects [childDv, energy, delta] on stack (top-down)
            // Operands come from resolveOperands() - no direct stack access needed
            if (operands.size() < 3) { organism.instructionFailed("FRKS requires 3 values on stack."); return; }
            Object dvObj = operands.get(0).value();      // top of stack = first operand
            Object energyObj = operands.get(1).value();
            Object deltaObj = operands.get(2).value();
            if (!(dvObj instanceof int[] childDv) || !(energyObj instanceof Integer ei) || !(deltaObj instanceof int[] delta)) {
                organism.instructionFailed("FRKS stack contents invalid.");
                return;
            }
            if (!organism.isUnitVector(delta)) {
                return;
            }
            int energy = org.evochora.runtime.model.Molecule.fromInt(ei).toScalarValue();
            // The VirtualMachine already deducted the base cost (1), now we need to deduct the energy given to child
            if (energy > 0 && organism.getEr() >= energy) {
                int[] childIp = organism.getTargetCoordinate(organism.getActiveDp(), delta, environment);
                organism.takeEr(energy); // Deduct the energy given to the child
                Organism child = Organism.create(simulation, childIp, energy, organism.getLogger());
                child.setDv(childDv);
                child.setParentId(organism.getId());
                child.setBirthTick(simulation.getCurrentTick());
                child.setProgramId(organism.getProgramId());
                simulation.addNewOrganism(child);

                // Transfer ownership of molecules with matching marker to the child
                int parentMr = organism.getMr();
                environment.transferOwnership(organism.getId(), child.getId(), parentMr);
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

    private void handleTrns(List<Operand> operands) {
        // Operand comes from resolveOperands() - no direct stack access needed
        if (operands.isEmpty()) { organism.instructionFailed("TRNS requires vector on stack."); return; }
        Object top = operands.get(0).value();
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
                // Operand comes from resolveOperands() - no direct stack access needed
                if (operands.isEmpty()) { organism.instructionFailed("ADPS requires index on stack."); return; }
                Object raw = operands.get(0).value();
                if (!(raw instanceof Integer i)) { organism.instructionFailed("ADPS requires scalar on stack."); return; }
                int idx = Molecule.fromInt(i).toScalarValue();
                organism.setActiveDpIndex(idx);
                break;
            }
        }
    }

    private void handleScanPassableNeighbors(String opName, List<Operand> operands, Environment environment) {
        int dims = environment.getShape().length;
        int scanDims = Math.min(dims, Config.VALUE_BITS / 2);
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
        int scanDims = Math.min(dims, Config.VALUE_BITS / 2);
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

    /**
     * Handles the GDVR and GDVS instructions (Get DV to Register/Stack).
     * Places the current DV value into the specified register or on the data stack.
     * @param opName The instruction name (GDVR or GDVS)
     * @param operands The operands (only used for GDVR)
     */
    private void handleGdv(String opName, List<Operand> operands) {
        int[] currentDv = organism.getDv();
        
        if ("GDVS".equals(opName)) {
            if (!operands.isEmpty()) {
                organism.instructionFailed("GDVS expects no operands."); 
                return; 
            }
            organism.getDataStack().push(currentDv);
        } else {
            // GDVR
            if (operands.size() != 1) { 
                organism.instructionFailed("GDVR requires one register operand."); 
                return; 
            }
            int targetReg = operands.get(0).rawSourceId();
            writeOperand(targetReg, currentDv);
        }
    }

    /**
     * Handles the SMR, SMRI, and SMRS instructions (Set Molecule marker Register).
     * Sets the organism's MR register to the value from the operand.
     * <p>
     * The operand must be of type DATA. The value is masked to MARKER_BITS (4 bits).
     * If the operand type is not DATA, the instruction fails.
     *
     * @param opName   The instruction name (SMR, SMRI, or SMRS)
     * @param operands The operands containing the value to set
     */
    private void handleSmr(String opName, List<Operand> operands) {
        Molecule source;

        if ("SMRS".equals(opName)) {
            // Stack variant: operand comes from resolveOperands() - no direct stack access needed
            if (operands.isEmpty()) {
                organism.instructionFailed("SMRS requires a value on the data stack.");
                return;
            }
            Object stackValue = operands.get(0).value();
            if (!(stackValue instanceof Integer intValue)) {
                organism.instructionFailed("SMRS requires a scalar value on the stack.");
                return;
            }
            source = Molecule.fromInt(intValue);
        } else {
            // Register or Immediate variant
            if (operands.size() != 1) {
                organism.instructionFailed("Invalid operands for " + opName + ".");
                return;
            }
            Object value = operands.get(0).value();
            if (!(value instanceof Integer intValue)) {
                organism.instructionFailed(opName + " requires a scalar operand.");
                return;
            }
            source = Molecule.fromInt(intValue);
        }

        // Type check: must be DATA
        if (source.type() != Config.TYPE_DATA) {
            organism.instructionFailed(opName + " requires DATA type operand.");
            return;
        }

        // Extract value and mask to valid marker range
        int markerValue = source.value() & Config.MARKER_VALUE_MASK;
        organism.setMr(markerValue);
    }

    /**
     * Handles the GMR and GMRS instructions (Get Molecule marker Register).
     * Reads the organism's MR register value and places it in a register or on the stack.
     *
     * @param opName   The instruction name (GMR or GMRS)
     * @param operands The operands (only used for GMR)
     */
    private void handleGmr(String opName, List<Operand> operands) {
        int mrValue = organism.getMr();
        Molecule result = new Molecule(Config.TYPE_DATA, mrValue);

        if ("GMRS".equals(opName)) {
            if (!operands.isEmpty()) {
                organism.instructionFailed("GMRS expects no operands.");
                return;
            }
            organism.getDataStack().push(result.toInt());
        } else {
            // GMR - register variant
            if (operands.size() != 1) {
                organism.instructionFailed("GMR requires one register operand.");
                return;
            }
            int targetReg = operands.get(0).rawSourceId();
            writeOperand(targetReg, result.toInt());
        }
    }

    /**
     * Handles the CMR, CMRI, and CMRS instructions (Clear Markers).
     * Removes all molecules owned by this organism that have a matching marker value.
     * <p>
     * This is used during reproduction when a replication attempt is aborted - the partially
     * replicated molecules are completely removed from the environment.
     *
     * @param opName      The instruction name (CMR, CMRI, or CMRS)
     * @param operands    The operands containing the marker value to match
     * @param environment The environment to operate on
     */
    private void handleCmr(String opName, List<Operand> operands, Environment environment) {
        Molecule source;

        if ("CMRS".equals(opName)) {
            // Stack variant
            if (operands.isEmpty()) {
                organism.instructionFailed("CMRS requires a value on the data stack.");
                return;
            }
            Object stackValue = operands.get(0).value();
            if (!(stackValue instanceof Integer intValue)) {
                organism.instructionFailed("CMRS requires a scalar value on the stack.");
                return;
            }
            source = Molecule.fromInt(intValue);
        } else {
            // Register or Immediate variant
            if (operands.size() != 1) {
                organism.instructionFailed("Invalid operands for " + opName + ".");
                return;
            }
            Object value = operands.get(0).value();
            if (!(value instanceof Integer intValue)) {
                organism.instructionFailed(opName + " requires a scalar operand.");
                return;
            }
            source = Molecule.fromInt(intValue);
        }

        // Type check: must be DATA
        if (source.type() != Config.TYPE_DATA) {
            organism.instructionFailed(opName + " requires DATA type operand.");
            return;
        }

        // Extract value and mask to valid marker range
        int markerToMatch = source.value() & Config.MARKER_VALUE_MASK;

        // Remove all own molecules with matching marker
        environment.clearMarkersFor(organism.getId(), markerToMatch);
    }
}
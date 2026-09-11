package org.evochora.runtime.isa.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Variant;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.LocationValue;
import org.evochora.runtime.model.Organism;

import java.util.Deque;
import java.util.List;

import static org.evochora.runtime.isa.Instruction.OperandSource.*;

/**
 * Handles location-related instructions, which manipulate the location stack
 * and location registers.
 */
public class LocationInstruction extends Instruction {

    private static int family;

    /**
     * Registers all location instructions with the instruction registry.
     * <p>
     * Note: In the Location family, the "L" variant means LOCATION_REGISTER, not LABEL.
     * The "S" variant here means "operates on location stack" (no encoded operand).
     *
     * @param f the family ID for this instruction family
     */
    public static void register(int f) {
        family = f;
        // Operation 0: DPL (Duplicate location)
        reg(0, Variant.S, "DPLS");
        reg(0, Variant.L, "DPLR", LOCATION_REGISTER);
        // Operation 1: SKL (Skip location)
        reg(1, Variant.S, "SKLS");
        reg(1, Variant.L, "SKLR", LOCATION_REGISTER);
        // Operation 2: LRD (Location register displacement)
        reg(2, Variant.L, "LRDS", LOCATION_REGISTER);
        reg(2, Variant.RL, "LRDR", REGISTER, LOCATION_REGISTER);
        // Operation 3: LSD (Location stack displacement)
        reg(3, Variant.NONE, "LSDS");
        reg(3, Variant.R, "LSDR", REGISTER);
        // Operation 4: PUSL (Push location)
        reg(4, Variant.L, "PUSL", LOCATION_REGISTER);
        // Operation 5: POPL (Pop location)
        reg(5, Variant.L, "POPL", LOCATION_REGISTER);
        // Operation 6: DUPL (Duplicate top of location stack)
        reg(6, Variant.NONE, "DUPL");
        // Operation 7: SWPL (Swap location stack)
        reg(7, Variant.NONE, "SWPL");
        // Operation 8: DRPL (Drop from location stack)
        reg(8, Variant.NONE, "DRPL");
        // Operation 9: ROTL (Rotate location stack)
        reg(9, Variant.NONE, "ROTL");
        // Operation 10: CRL (Clear location register)
        reg(10, Variant.L, "CRLR", LOCATION_REGISTER);
        // Operation 11: LRL (Load location register)
        reg(11, Variant.LL, "LRLR", LOCATION_REGISTER, LOCATION_REGISTER);
        // Operation 12: SKJ (Seek Jump - DP fuzzy jump to label)
        reg(12, Variant.L, "SKJI", LABEL);
        reg(12, Variant.R, "SKJR", REGISTER);
        reg(12, Variant.S, "SKJS", STACK);
        // Operation 13: PSL (Push Location from Label — resolve label via fuzzy matching, push position onto LS)
        reg(13, Variant.L, "PSLI", LABEL);
        // Operation 14: LRI (Location Register from Label Immediate — resolve label, write position to location register)
        reg(14, Variant.LL, "LRLI", LOCATION_REGISTER, LABEL);
    }

    private static void reg(int op, int variant, String name, OperandSource... sources) {
        Instruction.registerOp(LocationInstruction.class, LocationInstruction::new, family, op, variant, name, true, sources);
    }

    /**
     * Constructs a new LocationInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public LocationInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context) {
        Organism org = context.getOrganism();
        Environment env = context.getWorld();
        String name = getName();
        List<Operand> ops = resolveOperands(env);
        if (org.isInstructionFailed()) {
            return;
        }

        Deque<int[]> ls = org.getLocationStack();

        switch (name) {
            case "DUPL": {
                if (ls.isEmpty()) { org.instructionFailed("DUPL on empty LS"); return; }
                if (!org.pushLocation(ls.peek())) { return; }
                break;
            }
            case "SWPL": {
                if (ls.size() < 2) { org.instructionFailed("SWPL requires 2 elements on LS"); return; }
                if (!org.requireLocationStackWithinLimit()) { return; }
                int[] a = ls.pop();
                int[] b = ls.pop();
                if (!org.pushLocation(a)) { return; }
                if (!org.pushLocation(b)) { return; }
                break;
            }
            case "DRPL": {
                if (ls.isEmpty()) { org.instructionFailed("DRPL on empty LS"); return; }
                ls.pop();
                break;
            }
            case "ROTL": {
                if (ls.size() < 3) { org.instructionFailed("ROTL requires 3 elements on LS"); return; }
                if (!org.requireLocationStackWithinLimit()) { return; }
                int[] a = ls.pop();
                int[] b = ls.pop();
                int[] c = ls.pop();
                if (!org.pushLocation(b)) { return; }
                if (!org.pushLocation(c)) { return; }
                if (!org.pushLocation(a)) { return; }
                break;
            }
            case "DPLR": {
                if (ops.size() != 1) { org.instructionFailed("DPLR expects %LR<Index>"); return; }
                if (!writeLocationOperand(ops.get(0).rawSourceId(), org.getActiveDp())) { return; }
                break;
            }
            case "DPLS": {
                if (!org.pushLocation(org.getActiveDp())) { return; }
                break;
            }
            case "SKLR": {
                if (ops.size() != 1) { org.instructionFailed("SKLR expects %LR<Index>"); return; }
                Object val = org.readOperand(ops.get(0).rawSourceId());
                if (org.isInstructionFailed()) { return; }
                int[] target = (int[]) val;
                if (LocationValue.isNone(target)) {
                    org.instructionFailed("SKLR: location register holds no position");
                    return;
                }
                org.setActiveDp(target);
                break;
            }
            case "SKLS": {
                if (ls.isEmpty()) { org.instructionFailed("SKLS on empty LS"); return; }
                // Read before popping: an entry that holds no position leaves the stack as it is,
                // so the value is still there for a program that reacts to the failure. Setting the
                // data pointer afterwards cannot fail — its index was checked where it was set —
                // so the pop has nothing left to risk.
                int[] target = ls.peek();
                if (LocationValue.isNone(target)) {
                    org.instructionFailed("SKLS: top of LS holds no position");
                    return;
                }
                ls.pop();
                org.setActiveDp(target);
                break;
            }
            case "PUSL": {
                if (ops.size() != 1) { org.instructionFailed("PUSL expects %LR<Index>"); return; }
                Object val = org.readOperand(ops.get(0).rawSourceId());
                if (org.isInstructionFailed()) { return; }
                if (!org.pushLocation((int[]) val)) { return; }
                break;
            }
            case "POPL": {
                if (ops.size() != 1) { org.instructionFailed("POPL expects %LR<Index>"); return; }
                if (ls.isEmpty()) { org.instructionFailed("POPL on empty LS"); return; }
                // The entry is taken only once it has a register to go to.
                int[] vec = ls.peek();
                if (!writeLocationOperand(ops.get(0).rawSourceId(), vec)) { return; }
                ls.pop();
                break;
            }
            case "LRDR": {
                if (ops.size() != 2) { org.instructionFailed("LRDR expects <Dest_Reg>, %LR<Index>"); return; }
                Object val = org.readOperand(ops.get(1).rawSourceId());
                if (org.isInstructionFailed()) { return; }
                if (LocationValue.isNone((int[]) val)) {
                    org.instructionFailed("LRDR: location register holds no position");
                    return;
                }
                if (!writeOperand(ops.get(0).rawSourceId(), val)) { return; }
                break;
            }
            case "LRDS": {
                if (ops.size() != 1) { org.instructionFailed("LRDS expects %LR<Index>"); return; }
                Object val = org.readOperand(ops.get(0).rawSourceId());
                if (org.isInstructionFailed()) { return; }
                if (LocationValue.isNone((int[]) val)) {
                    org.instructionFailed("LRDS: location register holds no position");
                    return;
                }
                if (!org.pushData(val)) { return; }
                break;
            }
            case "LSDR": {
                if (ops.size() != 1) { org.instructionFailed("LSDR expects <Dest_Reg>"); return; }
                int destReg = ops.get(0).rawSourceId();
                if (ls.isEmpty()) { org.instructionFailed("LSDR on empty LS"); return; }
                int[] vec = ls.peek();
                if (LocationValue.isNone(vec)) {
                    org.instructionFailed("LSDR: top of LS holds no position");
                    return;
                }
                if (!writeOperand(destReg, vec)) { return; }
                break;
            }
            case "LSDS": {
                if (ls.isEmpty()) { org.instructionFailed("LSDS on empty LS"); return; }
                // Every check comes before the pop, so a failure leaves the stack as it was.
                int[] vec = ls.peek();
                if (LocationValue.isNone(vec)) {
                    org.instructionFailed("LSDS: top of LS holds no position");
                    return;
                }
                if (!org.requireDataStackRoom()) { return; }
                ls.pop();
                if (!org.pushData(vec)) { return; }
                break;
            }
            case "LRLR": {
                if (ops.size() != 2) { org.instructionFailed("LRLR expects <dest_LR>, <src_LR>"); return; }
                Object srcVal = org.readOperand(ops.get(1).rawSourceId());
                if (org.isInstructionFailed()) { return; }
                int[] vecCopy = ((int[]) srcVal).clone();
                if (!writeLocationOperand(ops.get(0).rawSourceId(), vecCopy)) { return; }
                break;
            }
            case "CRLR": {
                if (ops.size() != 1) { org.instructionFailed("CRLR expects <LR>"); return; }
                if (!writeLocationOperand(ops.get(0).rawSourceId(), LocationValue.NONE)) { return; }
                break;
            }
            case "SKJI":
            case "SKJR":
            case "SKJS": {
                // SKJ* - Seek Jump: move active DP to label using fuzzy matching
                if (ops.isEmpty()) {
                    org.instructionFailed("SKJ requires label hash operand.");
                    return;
                }

                int labelHash;
                if ("SKJI".equals(name)) {
                    // Immediate variant: hash already resolved in resolveOperands()
                    labelHash = (Integer) ops.get(0).value();
                } else if ("SKJR".equals(name)) {
                    // Register variant: extract hash from register value
                    Object raw = ops.get(0).value();
                    if (!(raw instanceof Integer i)) {
                        org.instructionFailed("SKJR register must hold scalar.");
                        return;
                    }
                    labelHash = Molecule.fromInt(i).toScalarValue() & Config.VALUE_MASK;
                } else {
                    // Stack variant: extract hash from stack value
                    Object raw = ops.get(0).value();
                    if (!(raw instanceof Integer i)) {
                        org.instructionFailed("SKJS requires scalar on stack.");
                        return;
                    }
                    labelHash = Molecule.fromInt(i).toScalarValue() & Config.VALUE_MASK;
                }

                // Find target label using fuzzy matching (from active DP position)
                int[] targetCoords = resolveLabelTarget(labelHash, org.getActiveDp(), org, env);
                if (targetCoords == null) {
                    org.instructionFailed("SKJ: No matching label found for hash " + labelHash);
                    return;
                }

                if (!validateOwnership(targetCoords, org, env, "SKJ")) return;

                org.setActiveDp(targetCoords);
                break;
            }
            case "PSLI": {
                if (ops.isEmpty()) { org.instructionFailed("PSLI requires label hash operand."); return; }
                Object hashObj = ops.get(0).value();
                if (!(hashObj instanceof Integer labelHash)) {
                    org.instructionFailed("PSLI: label hash must be integer");
                    return;
                }
                int[] targetCoords = resolveLabelTarget(labelHash, org.getActiveDp(), org, env);
                if (targetCoords == null) {
                    org.instructionFailed("PSLI: No matching label found for hash " + labelHash);
                    return;
                }
                if (!validateOwnership(targetCoords, org, env, "PSLI")) return;
                if (!org.pushLocation(targetCoords)) { return; }
                break;
            }
            case "LRLI": {
                if (ops.size() < 2) { org.instructionFailed("LRLI requires location register and label hash operands."); return; }
                int destRegId = ops.get(0).rawSourceId();
                Object hashObj = ops.get(1).value();
                if (!(hashObj instanceof Integer labelHash)) {
                    org.instructionFailed("LRLI: label hash must be integer");
                    return;
                }
                int[] targetCoords = resolveLabelTarget(labelHash, org.getActiveDp(), org, env);
                if (targetCoords == null) {
                    org.instructionFailed("LRLI: No matching label found for hash " + labelHash);
                    return;
                }
                if (!validateOwnership(targetCoords, org, env, "LRLI")) return;
                if (!writeLocationOperand(destRegId, targetCoords)) return;
                break;
            }
            default:
                org.instructionFailed("Unknown location instruction: " + name);
        }
    }
}


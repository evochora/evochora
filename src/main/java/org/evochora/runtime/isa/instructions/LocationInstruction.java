package org.evochora.runtime.isa.instructions;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Variant;
import org.evochora.runtime.model.Environment;
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
    }

    private static void reg(int op, int variant, String name, OperandSource... sources) {
        Instruction.registerOp(LocationInstruction.class, family, op, variant, name, sources);
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
    public void execute(ExecutionContext context, ProgramArtifact artifact) {
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
                ls.push(ls.peek());
                break;
            }
            case "SWPL": {
                if (ls.size() < 2) { org.instructionFailed("SWPL requires 2 elements on LS"); return; }
                int[] a = ls.pop();
                int[] b = ls.pop();
                ls.push(a); ls.push(b);
                break;
            }
            case "DRPL": {
                if (ls.isEmpty()) { org.instructionFailed("DRPL on empty LS"); return; }
                ls.pop();
                break;
            }
            case "ROTL": {
                if (ls.size() < 3) { org.instructionFailed("ROTL requires 3 elements on LS"); return; }
                int[] a = ls.pop(); // top element
                int[] b = ls.pop(); // middle element
                int[] c = ls.pop(); // bottom element
                // ROTL rotates the top 3 elements: [A, B, C] -> [C, A, B]
                ls.push(b); ls.push(c); ls.push(a);
                break;
            }
            case "DPLR": {
                if (ops.size() != 1) { org.instructionFailed("DPLR expects %LR<Index>"); return; }
                int lrIdx = ops.get(0).rawSourceId();
                org.setLr(lrIdx, org.getActiveDp());
                break;
            }
            case "DPLS": {
                ls.push(org.getActiveDp());
                break;
            }
            case "SKLR": {
                if (ops.size() != 1) { org.instructionFailed("SKLR expects %LR<Index>"); return; }
                int lrIdx = ops.get(0).rawSourceId();
                int[] target = org.getLr(lrIdx);
                if (target == null) { org.instructionFailed("Invalid LR index"); return; }
                org.setActiveDp(target);
                break;
            }
            case "SKLS": {
                if (ls.isEmpty()) { org.instructionFailed("SKLS on empty LS"); return; }
                int[] target = ls.pop();
                org.setActiveDp(target);
                break;
            }
            case "PUSL": {
                if (ops.size() != 1) { org.instructionFailed("PUSL expects %LR<Index>"); return; }
                int lrIdx = ops.get(0).rawSourceId();
                int[] vec = org.getLr(lrIdx);
                if (vec == null) { org.instructionFailed("Invalid LR index"); return; }
                org.getLocationStack().push(vec);
                break;
            }
            case "POPL": {
                if (ops.size() != 1) { org.instructionFailed("POPL expects %LR<Index>"); return; }
                if (ls.isEmpty()) { org.instructionFailed("POPL on empty LS"); return; }
                int lrIdx = ops.get(0).rawSourceId();
                int[] vec = ls.pop();
                org.setLr(lrIdx, vec);
                break;
            }
            case "LRDR": {
                if (ops.size() != 2) { org.instructionFailed("LRDR expects <Dest_Reg>, %LR<Index>"); return; }
                int destReg = ops.get(0).rawSourceId();
                int lrIdx = ops.get(1).rawSourceId();
                int[] vec = org.getLr(lrIdx);
                if (vec == null) { org.instructionFailed("Invalid LR index"); return; }
                if (!writeOperand(destReg, vec)) {
                    return;
                }
                break;
            }
            case "LRDS": {
                if (ops.size() != 1) { org.instructionFailed("LRDS expects %LR<Index>"); return; }
                int lrIdx = ops.get(0).rawSourceId();
                int[] vec = org.getLr(lrIdx);
                if (vec == null) { org.instructionFailed("Invalid LR index"); return; }
                org.getDataStack().push(vec);
                break;
            }
            case "LSDR": {
                if (ops.size() != 1) { org.instructionFailed("LSDR expects <Dest_Reg>"); return; }
                int destReg = ops.get(0).rawSourceId();
                if (ls.isEmpty()) { org.instructionFailed("LSDR on empty LS"); return; }
                int[] vec = ls.peek();
                if (!writeOperand(destReg, vec)) {
                    return;
                }
                break;
            }
            case "LSDS": {
                if (ls.isEmpty()) { org.instructionFailed("LSDS on empty LS"); return; }
                int[] vec = ls.pop();
                org.getDataStack().push(vec);
                break;
            }
            case "LRLR": {
                if (ops.size() != 2) { org.instructionFailed("LRLR expects <dest_LR>, <src_LR>"); return; }
                int destLrIdx = ops.get(0).rawSourceId();
                int srcLrIdx = ops.get(1).rawSourceId();
                
                // Validate that both operands are LR registers
                if (destLrIdx < 0 || destLrIdx >= Config.NUM_LOCATION_REGISTERS) {
                    org.instructionFailed("LRLR: Invalid destination LR index: " + destLrIdx);
                    return;
                }
                if (srcLrIdx < 0 || srcLrIdx >= Config.NUM_LOCATION_REGISTERS) {
                    org.instructionFailed("LRLR: Invalid source LR index: " + srcLrIdx);
                    return;
                }
                
                // Get source vector and copy to destination
                int[] srcVec = org.getLr(srcLrIdx);
                if (srcVec == null) {
                    org.instructionFailed("LRLR: Source LR" + srcLrIdx + " contains null vector");
                    return;
                }
                
                // Create a copy of the vector to avoid reference sharing
                int[] vecCopy = srcVec.clone();
                org.setLr(destLrIdx, vecCopy);
                break;
            }
            case "CRLR": {
                if (ops.size() != 1) { org.instructionFailed("CRLR expects <LR>"); return; }
                int lrIdx = ops.get(0).rawSourceId();
                
                // Validate that the operand is an LR register
                if (lrIdx < 0 || lrIdx >= Config.NUM_LOCATION_REGISTERS) {
                    org.instructionFailed("CRLR: Invalid LR index: " + lrIdx);
                    return;
                }
                
                // Set the LR to [0, 0]
                org.setLr(lrIdx, new int[]{0, 0});
                break;
            }
            default:
                org.instructionFailed("Unknown location instruction: " + name);
        }
    }
}


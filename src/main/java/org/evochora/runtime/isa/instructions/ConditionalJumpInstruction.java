package org.evochora.runtime.isa.instructions;

import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;

import java.util.List;

import static org.evochora.runtime.isa.Instruction.OperandSource.*;

/**
 * The conditional jumps: each tests a condition and jumps to its label when it holds; when it does
 * not, execution runs on with the next instruction. The conditions are evaluated by
 * {@link AbstractConditionInstruction}, and every conditional jump has a conditional skip as its
 * twin: the same condition, the same operands, a label added as the last one, and the same
 * operation and index within its family.
 * <p>
 * The label is resolved only when the condition holds, the way {@code JMPI} resolves it. Without a
 * matching label the instruction fails as {@code JMPI} does, and execution runs on with the next
 * instruction.
 */
public class ConditionalJumpInstruction extends AbstractConditionInstruction {

    private static int family;

    /**
     * Registers all conditional jumps with the instruction registry.
     * <p>
     * Every conditional jump is registered together with its negation, the instruction that jumps
     * exactly when this one does not.
     *
     * @param f the family ID for this instruction family
     */
    public static void register(int f) {
        family = f;
        // Operations 0 and 1: equal / not equal
        regPair(Condition.EQUAL, 0, 1, 0, 1, "JFR", "JNR", REGISTER, REGISTER, LABEL);
        regPair(Condition.EQUAL, 0, 1, 2, 3, "JFI", "JNI", REGISTER, IMMEDIATE, LABEL);
        regPair(Condition.EQUAL, 0, 1, 4, 5, "JFS", "JNS", STACK, STACK, LABEL);
        // Operations 2 and 5: less than / greater than or equal
        regPair(Condition.LESS_THAN, 2, 5, 6, 7, "JLTR", "JGER", REGISTER, REGISTER, LABEL);
        regPair(Condition.LESS_THAN, 2, 5, 8, 9, "JLTI", "JGEI", REGISTER, IMMEDIATE, LABEL);
        regPair(Condition.LESS_THAN, 2, 5, 10, 11, "JLTS", "JGES", STACK, STACK, LABEL);
        // Operations 3 and 4: greater than / less than or equal
        regPair(Condition.GREATER_THAN, 3, 4, 12, 13, "JGTR", "JLER", REGISTER, REGISTER, LABEL);
        regPair(Condition.GREATER_THAN, 3, 4, 14, 15, "JGTI", "JLEI", REGISTER, IMMEDIATE, LABEL);
        regPair(Condition.GREATER_THAN, 3, 4, 16, 17, "JGTS", "JLES", STACK, STACK, LABEL);
        // Operations 6 and 7: true (non-zero) / not true (zero)
        regPair(Condition.TYPE_EQUAL, 6, 7, 18, 19, "JFTR", "JNTR", REGISTER, REGISTER, LABEL);
        regPair(Condition.TYPE_EQUAL, 6, 7, 20, 21, "JFTI", "JNTI", REGISTER, IMMEDIATE, LABEL);
        regPair(Condition.TYPE_EQUAL, 6, 7, 22, 23, "JFTS", "JNTS", STACK, STACK, LABEL);
        // Operations 8 and 9: mine / not mine (ownership check)
        regPair(Condition.MINE, 8, 9, 24, 25, "JFMR", "JNMR", REGISTER, LABEL);
        regPair(Condition.MINE, 8, 9, 26, 27, "JFMI", "JNMI", VECTOR, LABEL);  // Note: uses VECTOR operand despite "I" suffix
        regPair(Condition.MINE, 8, 9, 28, 29, "JFMS", "JNMS", STACK, LABEL);
        // Operations 10 and 11: passable / not passable
        regPair(Condition.PASSABLE, 10, 11, 30, 31, "JFPR", "JNPR", REGISTER, LABEL);
        regPair(Condition.PASSABLE, 10, 11, 32, 33, "JFPI", "JNPI", VECTOR, LABEL);  // Note: uses VECTOR operand despite "I" suffix
        regPair(Condition.PASSABLE, 10, 11, 34, 35, "JFPS", "JNPS", STACK, LABEL);
        // Operations 12 and 13: foreign ownership / not foreign ownership
        regPair(Condition.FOREIGN, 12, 13, 36, 37, "JFFR", "JNFR", REGISTER, LABEL);
        regPair(Condition.FOREIGN, 12, 13, 38, 39, "JFFI", "JNFI", VECTOR, LABEL);  // Note: uses VECTOR operand despite "I" suffix
        regPair(Condition.FOREIGN, 12, 13, 40, 41, "JFFS", "JNFS", STACK, LABEL);
        // Operations 14 and 15: vacant ownership / not vacant ownership
        regPair(Condition.VACANT, 14, 15, 42, 43, "JFVR", "JNVR", REGISTER, LABEL);
        regPair(Condition.VACANT, 14, 15, 44, 45, "JFVI", "JNVI", VECTOR, LABEL);  // Note: uses VECTOR operand despite "I" suffix
        regPair(Condition.VACANT, 14, 15, 46, 47, "JFVS", "JNVS", STACK, LABEL);
        // Operations 16 and 17: previous instruction failed / did not fail
        regPair(Condition.PREVIOUS_FAILED, 16, 17, 48, 49, "JFER", "JNER", LABEL);
        // Operations 18 and 19: location register holds a position / holds none
        regPair(Condition.HOLDS_POSITION, 18, 19, 50, 51, "JFSL", "JNSL", LOCATION_REGISTER, LABEL);
        // Operations 20 and 21: greater than a draw below the second value / less than or equal to it
        regPair(Condition.GREATER_THAN_DRAW, 20, 21, 52, 53, "QGTR", "QLER", REGISTER, REGISTER, LABEL);
        regPair(Condition.GREATER_THAN_DRAW, 20, 21, 54, 55, "QGTI", "QLEI", REGISTER, IMMEDIATE, LABEL);
        regPair(Condition.GREATER_THAN_DRAW, 20, 21, 56, 57, "QGTS", "QLES", STACK, STACK, LABEL);
        // Operations 22 and 23: less than a draw below the second value / greater than or equal to it
        regPair(Condition.LESS_THAN_DRAW, 22, 23, 58, 59, "QLTR", "QGER", REGISTER, REGISTER, LABEL);
        regPair(Condition.LESS_THAN_DRAW, 22, 23, 60, 61, "QLTI", "QGEI", REGISTER, IMMEDIATE, LABEL);
        regPair(Condition.LESS_THAN_DRAW, 22, 23, 62, 63, "QLTS", "QGES", STACK, STACK, LABEL);
        // Operations 24 and 25: within the own body on a line / not within it
        regPair(Condition.WITHIN_BODY, 24, 25, 64, 65, "JFBR", "JNBR", REGISTER, LABEL);
        regPair(Condition.WITHIN_BODY, 24, 25, 66, 67, "JFBI", "JNBI", VECTOR, LABEL);  // Note: uses VECTOR operand despite "I" suffix
        regPair(Condition.WITHIN_BODY, 24, 25, 68, 69, "JFBS", "JNBS", STACK, LABEL);
        // Operations 26 and 27: the cell exists / does not exist (beyond the edge of a bounded world)
        regPair(Condition.EXISTS, 26, 27, 70, 71, "JFXR", "JNXR", REGISTER, LABEL);
        regPair(Condition.EXISTS, 26, 27, 72, 73, "JFXI", "JNXI", VECTOR, LABEL);  // Note: uses VECTOR operand despite "I" suffix
        regPair(Condition.EXISTS, 26, 27, 74, 75, "JFXS", "JNXS", STACK, LABEL);
    }

    /**
     * Registers a conditional jump and its negation, one variant of each, with the same operands.
     */
    private static void regPair(Condition condition, int op, int negatedOp, int index, int negatedIndex,
                                String name, String negatedName, OperandSource... sources) {
        regPair(ConditionalJumpInstruction.class, ConditionalJumpInstruction::new, family, condition,
                op, negatedOp, index, negatedIndex, name, negatedName, sources);
        declareLabelIsJumpTarget(name);
        declareLabelIsJumpTarget(negatedName);
    }

    /**
     * Constructs a new ConditionalJumpInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public ConditionalJumpInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    protected int operandsAfterCondition() {
        return 1;
    }

    @Override
    protected void act(boolean holds, List<Operand> operands, Environment environment) {
        if (!holds) {
            return;
        }
        if (!(operands.get(operands.size() - 1).value() instanceof Integer labelHash)) {
            organism.instructionFailed(getName() + " target must be a label hash.");
            return;
        }
        int[] targetIp = resolveLabelTarget(labelHash, organism.getIp(), organism, environment);
        if (targetIp == null) {
            organism.instructionFailed(getName() + ": No matching label found for hash "
                    + labelHashText(labelHash));
            return;
        }
        jumpTo(getName(), targetIp, organism, environment);
    }
}

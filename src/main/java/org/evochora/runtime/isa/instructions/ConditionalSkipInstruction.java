package org.evochora.runtime.isa.instructions;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;

import java.util.List;
import java.util.Optional;

import static org.evochora.runtime.isa.Instruction.OperandSource.*;

/**
 * The conditional skips: each tests a condition and skips the next instruction when it does not
 * hold. The conditions themselves are evaluated by {@link AbstractConditionInstruction}.
 */
public class ConditionalSkipInstruction extends AbstractConditionInstruction {

    private static int family;

    /**
     * Registers all conditional skips with the instruction registry.
     * <p>
     * Every conditional skip is registered together with its negation, the instruction that skips
     * the next instruction exactly when this one does not. The two share their operands, so
     * one can stand in for the other wherever a condition has to be inverted.
     *
     * @param f the family ID for this instruction family
     */
    public static void register(int f) {
        family = f;
        // Operations 0 and 1: equal / not equal
        regPair(Condition.EQUAL, 0, 1, 0, 1, "IFR", "INR", REGISTER, REGISTER);
        regPair(Condition.EQUAL, 0, 1, 2, 3, "IFI", "INI", REGISTER, IMMEDIATE);
        regPair(Condition.EQUAL, 0, 1, 4, 5, "IFS", "INS", STACK, STACK);
        // Operations 2 and 5: less than / greater than or equal
        regPair(Condition.LESS_THAN, 2, 5, 6, 7, "LTR", "GETR", REGISTER, REGISTER);
        regPair(Condition.LESS_THAN, 2, 5, 8, 9, "LTI", "GETI", REGISTER, IMMEDIATE);
        regPair(Condition.LESS_THAN, 2, 5, 10, 11, "LTS", "GETS", STACK, STACK);
        // Operations 3 and 4: greater than / less than or equal
        regPair(Condition.GREATER_THAN, 3, 4, 12, 13, "GTR", "LETR", REGISTER, REGISTER);
        regPair(Condition.GREATER_THAN, 3, 4, 14, 15, "GTI", "LETI", REGISTER, IMMEDIATE);
        regPair(Condition.GREATER_THAN, 3, 4, 16, 17, "GTS", "LETS", STACK, STACK);
        // Operations 6 and 7: true (non-zero) / not true (zero)
        regPair(Condition.TYPE_EQUAL, 6, 7, 18, 19, "IFTR", "INTR", REGISTER, REGISTER);
        regPair(Condition.TYPE_EQUAL, 6, 7, 20, 21, "IFTI", "INTI", REGISTER, IMMEDIATE);
        regPair(Condition.TYPE_EQUAL, 6, 7, 22, 23, "IFTS", "INTS", STACK, STACK);
        // Operations 8 and 9: mine / not mine (ownership check)
        regPair(Condition.MINE, 8, 9, 24, 25, "IFMR", "INMR", REGISTER);
        regPair(Condition.MINE, 8, 9, 26, 27, "IFMI", "INMI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        regPair(Condition.MINE, 8, 9, 28, 29, "IFMS", "INMS", STACK);
        // Operations 10 and 11: passable / not passable
        regPair(Condition.PASSABLE, 10, 11, 30, 31, "IFPR", "INPR", REGISTER);
        regPair(Condition.PASSABLE, 10, 11, 32, 33, "IFPI", "INPI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        regPair(Condition.PASSABLE, 10, 11, 34, 35, "IFPS", "INPS", STACK);
        // Operations 12 and 13: foreign ownership / not foreign ownership
        regPair(Condition.FOREIGN, 12, 13, 36, 37, "IFFR", "INFR", REGISTER);
        regPair(Condition.FOREIGN, 12, 13, 38, 39, "IFFI", "INFI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        regPair(Condition.FOREIGN, 12, 13, 40, 41, "IFFS", "INFS", STACK);
        // Operations 14 and 15: vacant ownership / not vacant ownership
        regPair(Condition.VACANT, 14, 15, 42, 43, "IFVR", "INVR", REGISTER);
        regPair(Condition.VACANT, 14, 15, 44, 45, "IFVI", "INVI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        regPair(Condition.VACANT, 14, 15, 46, 47, "IFVS", "INVS", STACK);
        // Operations 16 and 17: previous instruction failed / did not fail
        regPair(Condition.PREVIOUS_FAILED, 16, 17, 48, 49, "IFER", "INER");
        // Operations 18 and 19: location register holds a position / holds none
        regPair(Condition.HOLDS_POSITION, 18, 19, 50, 51, "IFSL", "INSL", LOCATION_REGISTER);
        // Operations 20 and 21: greater than a draw below the second value / less than or equal to it
        regPair(Condition.GREATER_THAN_DRAW, 20, 21, 52, 53, "PGTR", "PLER", REGISTER, REGISTER);
        regPair(Condition.GREATER_THAN_DRAW, 20, 21, 54, 55, "PGTI", "PLEI", REGISTER, IMMEDIATE);
        regPair(Condition.GREATER_THAN_DRAW, 20, 21, 56, 57, "PGTS", "PLES", STACK, STACK);
        // Operations 22 and 23: less than a draw below the second value / greater than or equal to it
        regPair(Condition.LESS_THAN_DRAW, 22, 23, 58, 59, "PLTR", "PGER", REGISTER, REGISTER);
        regPair(Condition.LESS_THAN_DRAW, 22, 23, 60, 61, "PLTI", "PGEI", REGISTER, IMMEDIATE);
        regPair(Condition.LESS_THAN_DRAW, 22, 23, 62, 63, "PLTS", "PGES", STACK, STACK);
        // Operations 24 and 25: within the own body on a line / not within it
        regPair(Condition.WITHIN_BODY, 24, 25, 64, 65, "IFBR", "INBR", REGISTER);
        regPair(Condition.WITHIN_BODY, 24, 25, 66, 67, "IFBI", "INBI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        regPair(Condition.WITHIN_BODY, 24, 25, 68, 69, "IFBS", "INBS", STACK);
        // Operations 26 and 27: the cell exists / does not exist (beyond the edge of a bounded world)
        regPair(Condition.EXISTS, 26, 27, 70, 71, "IFXR", "INXR", REGISTER);
        regPair(Condition.EXISTS, 26, 27, 72, 73, "IFXI", "INXI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        regPair(Condition.EXISTS, 26, 27, 74, 75, "IFXS", "INXS", STACK);
    }

    /**
     * Registers a conditional and its negation, one variant of each, with the same operands.
     */
    /**
     * Registers a conditional skip and its negation, one variant of each, with the same operands.
     */
    private static void regPair(Condition condition, int op, int negatedOp, int index, int negatedIndex,
                                String name, String negatedName, OperandSource... sources) {
        regPair(ConditionalSkipInstruction.class, ConditionalSkipInstruction::new, family, condition,
                op, negatedOp, index, negatedIndex, name, negatedName, sources);
        declareSkipsNext(name);
        declareSkipsNext(negatedName);
    }

    /**
     * Returns the conditional skip that skips the next instruction exactly when the given one does
     * not. The two take the same operands, so the negation can replace the original wherever a
     * condition has to be inverted.
     *
     * @param opcodeName the name of a conditional skip, in any letter case
     * @return the name of its negation, or empty if the name is not a conditional skip
     */
    public static Optional<String> negationOf(String opcodeName) {
        Integer opcodeId = Instruction.getInstructionIdByName(opcodeName.toUpperCase());
        if (opcodeId == null || !Instruction.skipsNext(opcodeId)) {
            return Optional.empty();
        }
        return negationByName(opcodeName);
    }

    /**
     * Constructs a new ConditionalSkipInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public ConditionalSkipInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    protected int operandsAfterCondition() {
        return 0;
    }

    @Override
    protected void act(boolean holds, List<Operand> operands, Environment environment) {
        if (!holds) {
            organism.skipNextInstruction(environment);
        }
    }
}

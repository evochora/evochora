package org.evochora.runtime.isa.instructions;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Variant;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import static org.evochora.runtime.isa.Instruction.OperandSource.*;

/**
 * Handles conditional instructions, which compare values and skip the next instruction
 * if the condition is not met. It supports different operand types and sources.
 */
public class ConditionalInstruction extends Instruction {

    private static int family;

    /**
     * Registers all conditional instructions with the instruction registry.
     *
     * @param f the family ID for this instruction family
     */
    public static void register(int f) {
        family = f;
        // Operation 0: IF/EQ (If Equal)
        reg(0, Variant.RR, "IFR", REGISTER, REGISTER);
        reg(0, Variant.RI, "IFI", REGISTER, IMMEDIATE);
        reg(0, Variant.SS, "IFS", STACK, STACK);
        // Operation 1: NE (Not Equal)
        reg(1, Variant.RR, "INR", REGISTER, REGISTER);
        reg(1, Variant.RI, "INI", REGISTER, IMMEDIATE);
        reg(1, Variant.SS, "INS", STACK, STACK);
        // Operation 2: LT (Less Than)
        reg(2, Variant.RR, "LTR", REGISTER, REGISTER);
        reg(2, Variant.RI, "LTI", REGISTER, IMMEDIATE);
        reg(2, Variant.SS, "LTS", STACK, STACK);
        // Operation 3: GT (Greater Than)
        reg(3, Variant.RR, "GTR", REGISTER, REGISTER);
        reg(3, Variant.RI, "GTI", REGISTER, IMMEDIATE);
        reg(3, Variant.SS, "GTS", STACK, STACK);
        // Operation 4: LE (Less Than or Equal)
        reg(4, Variant.RR, "LETR", REGISTER, REGISTER);
        reg(4, Variant.RI, "LETI", REGISTER, IMMEDIATE);
        reg(4, Variant.SS, "LETS", STACK, STACK);
        // Operation 5: GE (Greater Than or Equal)
        reg(5, Variant.RR, "GETR", REGISTER, REGISTER);
        reg(5, Variant.RI, "GETI", REGISTER, IMMEDIATE);
        reg(5, Variant.SS, "GETS", STACK, STACK);
        // Operation 6: IFT (If True / non-zero)
        reg(6, Variant.RR, "IFTR", REGISTER, REGISTER);
        reg(6, Variant.RI, "IFTI", REGISTER, IMMEDIATE);
        reg(6, Variant.SS, "IFTS", STACK, STACK);
        // Operation 7: INT (If Not True / zero)
        reg(7, Variant.RR, "INTR", REGISTER, REGISTER);
        reg(7, Variant.RI, "INTI", REGISTER, IMMEDIATE);
        reg(7, Variant.SS, "INTS", STACK, STACK);
        // Operation 8: IFM (If Mine - ownership check)
        reg(8, Variant.R, "IFMR", REGISTER);
        reg(8, Variant.V, "IFMI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        reg(8, Variant.S, "IFMS", STACK);
        // Operation 9: INM (If Not Mine - ownership check)
        reg(9, Variant.R, "INMR", REGISTER);
        reg(9, Variant.V, "INMI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        reg(9, Variant.S, "INMS", STACK);
        // Operation 10: IFP (If Passable)
        reg(10, Variant.R, "IFPR", REGISTER);
        reg(10, Variant.V, "IFPI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        reg(10, Variant.S, "IFPS", STACK);
        // Operation 11: INP (If Not Passable)
        reg(11, Variant.R, "INPR", REGISTER);
        reg(11, Variant.V, "INPI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        reg(11, Variant.S, "INPS", STACK);
        // Operation 12: IFF (If Foreign ownership)
        reg(12, Variant.R, "IFFR", REGISTER);
        reg(12, Variant.V, "IFFI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        reg(12, Variant.S, "IFFS", STACK);
        // Operation 13: INF (If Not Foreign ownership)
        reg(13, Variant.R, "INFR", REGISTER);
        reg(13, Variant.V, "INFI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        reg(13, Variant.S, "INFS", STACK);
        // Operation 14: IFV (If Vacant ownership)
        reg(14, Variant.R, "IFVR", REGISTER);
        reg(14, Variant.V, "IFVI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        reg(14, Variant.S, "IFVS", STACK);
        // Operation 15: INV (If Not Vacant ownership)
        reg(15, Variant.R, "INVR", REGISTER);
        reg(15, Variant.V, "INVI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        reg(15, Variant.S, "INVS", STACK);
        // Operation 16: IER (If Error - previous instruction failed)
        reg(16, Variant.NONE, "IFER");
        // Operation 17: INE (If No Error - previous instruction did not fail)
        reg(17, Variant.NONE, "INER");
    }

    private static void reg(int op, int variant, String name, OperandSource... sources) {
        Instruction.registerOp(ConditionalInstruction.class, ConditionalInstruction::new, family, op, variant, name, true, sources);
    }

    /**
     * Constructs a new ConditionalInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public ConditionalInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context, ProgramArtifact artifact) {
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();
        try {
            String opName = getName();
            if ("IFER".equals(opName) || "INER".equals(opName)) {
                boolean prevFailed = organism.wasPreviousInstructionFailed();
                boolean conditionMet = "IFER".equals(opName) ? prevFailed : !prevFailed;
                if (!conditionMet) {
                    organism.skipNextInstruction(environment);
                }
                return;
            }
            if (opName.startsWith("IFM") || opName.startsWith("INM")) {
                List<Operand> operands = resolveOperands(environment);
                if (organism.isInstructionFailed()) {
                    return;
                }
                if (operands.size() != 1) {
                    organism.instructionFailed("Invalid operand count for " + opName);
                    return;
                }
                Operand op = operands.get(0);
                if (!(op.value() instanceof int[])) {
                    organism.instructionFailed(opName + " requires a vector argument.");
                    return;
                }
                int[] vector = (int[]) op.value();
                if (!organism.isUnitVector(vector)) {
                    return;
                }
                int[] targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);
                int ownerId = environment.getOwnerId(targetCoordinate);
                boolean isAccessible = organism.isCellAccessible(ownerId);
                boolean conditionMet = opName.startsWith("IFM") ? isAccessible : !isAccessible;
                if (!conditionMet) {
                    organism.skipNextInstruction(environment);
                }
                return;
            }
            if (opName.startsWith("IFP") || opName.startsWith("INP")) {
                List<Operand> operands = resolveOperands(environment);
                if (organism.isInstructionFailed()) {
                    return;
                }
                if (operands.size() != 1) {
                    organism.instructionFailed("Invalid operand count for " + opName);
                    return;
                }
                Operand op = operands.get(0);
                if (!(op.value() instanceof int[])) {
                    organism.instructionFailed(opName + " requires a vector argument.");
                    return;
                }
                int[] vector = (int[]) op.value();
                if (!organism.isUnitVector(vector)) {
                    return;
                }
                int[] targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);
                Molecule molecule = environment.getMolecule(targetCoordinate);
                int ownerId = environment.getOwnerId(targetCoordinate);
                boolean isPassable = molecule.isEmpty() || organism.isCellAccessible(ownerId);
                boolean conditionMet = opName.startsWith("IFP") ? isPassable : !isPassable;
                if (!conditionMet) {
                    organism.skipNextInstruction(environment);
                }
                return;
            }
            if (opName.startsWith("IFF") || opName.startsWith("INF")) {
                // Foreign ownership check: ownerId != 0 && ownerId != self.id
                List<Operand> operands = resolveOperands(environment);
                if (organism.isInstructionFailed()) {
                    return;
                }
                if (operands.size() != 1) {
                    organism.instructionFailed("Invalid operand count for " + opName);
                    return;
                }
                Operand op = operands.get(0);
                if (!(op.value() instanceof int[])) {
                    organism.instructionFailed(opName + " requires a vector argument.");
                    return;
                }
                int[] vector = (int[]) op.value();
                if (!organism.isUnitVector(vector)) {
                    return;
                }
                int[] targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);
                int ownerId = environment.getOwnerId(targetCoordinate);
                boolean isForeign = (ownerId != 0 && ownerId != organism.getId());
                boolean conditionMet = opName.startsWith("IFF") ? isForeign : !isForeign;
                if (!conditionMet) {
                    organism.skipNextInstruction(environment);
                }
                return;
            }
            if (opName.startsWith("IFV") || opName.startsWith("INV")) {
                // Vacant ownership check: ownerId == 0
                List<Operand> operands = resolveOperands(environment);
                if (organism.isInstructionFailed()) {
                    return;
                }
                if (operands.size() != 1) {
                    organism.instructionFailed("Invalid operand count for " + opName);
                    return;
                }
                Operand op = operands.get(0);
                if (!(op.value() instanceof int[])) {
                    organism.instructionFailed(opName + " requires a vector argument.");
                    return;
                }
                int[] vector = (int[]) op.value();
                if (!organism.isUnitVector(vector)) {
                    return;
                }
                int[] targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);
                int ownerId = environment.getOwnerId(targetCoordinate);
                boolean isVacant = (ownerId == 0);
                boolean conditionMet = opName.startsWith("IFV") ? isVacant : !isVacant;
                if (!conditionMet) {
                    organism.skipNextInstruction(environment);
                }
                return;
            }
            List<Operand> operands = resolveOperands(environment);
            if (organism.isInstructionFailed()) {
                return;
            }
            if (operands.size() != 2) {
                organism.instructionFailed("Invalid operand count for conditional operation.");
                return;
            }

            Operand op1 = operands.get(0);
            Operand op2 = operands.get(1);
            boolean conditionMet = false;


            if (opName.startsWith("IFT") || opName.startsWith("INT")) { // Type comparison
                int type1 = (op1.value() instanceof Integer i) ? org.evochora.runtime.model.Molecule.fromInt(i).type() : -1; // -1 for vectors
                int type2 = (op2.value() instanceof Integer i) ? Molecule.fromInt(i).type() : -1;
                if (opName.startsWith("INT")) {
                    conditionMet = (type1 != type2);
                } else {
                    conditionMet = (type1 == type2);
                }
            } else { // Value comparison
                if (op1.value() instanceof int[] v1 && op2.value() instanceof int[] v2) {
                    boolean areEqual = Arrays.equals(v1, v2);
                    conditionMet = opName.startsWith("IN") ? !areEqual : areEqual;
                } else if (op1.value() instanceof Integer i1 && op2.value() instanceof Integer i2) {
                    Molecule s1 = org.evochora.runtime.model.Molecule.fromInt(i1);
                    Molecule s2 = org.evochora.runtime.model.Molecule.fromInt(i2);
                    if (Config.STRICT_TYPING && s1.type() != s2.type()) {
                        // Condition is false if types don't match in strict mode
                    } else {
                        int val1 = s1.toScalarValue();
                        int val2 = s2.toScalarValue();
                        switch (opName) {
                            case "IFR", "IFI", "IFS" -> conditionMet = (val1 == val2);
                            case "INR", "INI", "INS" -> conditionMet = (val1 != val2);
                            case "GTR", "GTI", "GTS" -> conditionMet = (val1 > val2);
                            case "GETR", "GETI", "GETS" -> conditionMet = (val1 >= val2);
                            case "LTR", "LTI", "LTS" -> conditionMet = (val1 < val2);
                            case "LETR", "LETI", "LETS" -> conditionMet = (val1 <= val2);
                            default -> organism.instructionFailed("Unknown conditional operation: " + opName);
                        }
                    }
                } else {
                    organism.instructionFailed("Mismatched operand types for comparison.");
                }
            }

            if (!conditionMet) {
                organism.skipNextInstruction(environment);
            }

        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during conditional operation.");
            return;
        }
    }
}
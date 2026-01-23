package org.evochora.runtime.isa.instructions;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Variant;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import java.util.List;
import java.util.NoSuchElementException;

import static org.evochora.runtime.isa.Instruction.OperandSource.*;

/**
 * Handles all arithmetic instructions, including scalar and vector operations.
 * It supports different operand sources like registers, immediate values, and the stack.
 */
public class ArithmeticInstruction extends Instruction {

    private static int family;

    /**
     * Registers all arithmetic instructions with the instruction registry.
     *
     * @param f the family ID for this instruction family
     */
    public static void register(int f) {
        family = f;
        // Operation 0: ADD
        reg(0, Variant.RR, "ADDR", REGISTER, REGISTER);
        reg(0, Variant.RI, "ADDI", REGISTER, IMMEDIATE);
        reg(0, Variant.SS, "ADDS", STACK, STACK);
        // Operation 1: SUB
        reg(1, Variant.RR, "SUBR", REGISTER, REGISTER);
        reg(1, Variant.RI, "SUBI", REGISTER, IMMEDIATE);
        reg(1, Variant.SS, "SUBS", STACK, STACK);
        // Operation 2: MUL
        reg(2, Variant.RR, "MULR", REGISTER, REGISTER);
        reg(2, Variant.RI, "MULI", REGISTER, IMMEDIATE);
        reg(2, Variant.SS, "MULS", STACK, STACK);
        // Operation 3: DIV
        reg(3, Variant.RR, "DIVR", REGISTER, REGISTER);
        reg(3, Variant.RI, "DIVI", REGISTER, IMMEDIATE);
        reg(3, Variant.SS, "DIVS", STACK, STACK);
        // Operation 4: MOD
        reg(4, Variant.RR, "MODR", REGISTER, REGISTER);
        reg(4, Variant.RI, "MODI", REGISTER, IMMEDIATE);
        reg(4, Variant.SS, "MODS", STACK, STACK);
        // Operation 5: NEG (negation)
        reg(5, Variant.R, "NEGR", REGISTER);
        reg(5, Variant.S, "NEGS", STACK);
        // Operation 6: ABS (absolute value)
        reg(6, Variant.R, "ABSR", REGISTER);
        reg(6, Variant.S, "ABSS", STACK);
        // Operation 7: INC (increment)
        reg(7, Variant.R, "INCR", REGISTER);
        reg(7, Variant.S, "INCS", STACK);
        // Operation 8: DEC (decrement)
        reg(8, Variant.R, "DECR", REGISTER);
        reg(8, Variant.S, "DECS", STACK);
        // Operation 9: MIN (minimum)
        reg(9, Variant.RR, "MINR", REGISTER, REGISTER);
        reg(9, Variant.RI, "MINI", REGISTER, IMMEDIATE);
        reg(9, Variant.SS, "MINS", STACK, STACK);
        // Operation 10: MAX (maximum)
        reg(10, Variant.RR, "MAXR", REGISTER, REGISTER);
        reg(10, Variant.RI, "MAXI", REGISTER, IMMEDIATE);
        reg(10, Variant.SS, "MAXS", STACK, STACK);
        // Operation 11: SGN (sign)
        reg(11, Variant.R, "SGNR", REGISTER);
        reg(11, Variant.S, "SGNS", STACK);
        // Operation 12: DOT (dot product)
        reg(12, Variant.RRR, "DOTR", REGISTER, REGISTER, REGISTER);
        reg(12, Variant.SS, "DOTS", STACK, STACK);
        // Operation 13: CRS (cross product)
        reg(13, Variant.RRR, "CRSR", REGISTER, REGISTER, REGISTER);
        reg(13, Variant.SS, "CRSS", STACK, STACK);
    }

    private static void reg(int op, int variant, String name, OperandSource... sources) {
        Instruction.registerOp(ArithmeticInstruction.class, family, op, variant, name, sources);
    }

    /**
     * Constructs a new ArithmeticInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public ArithmeticInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context, ProgramArtifact artifact) {
        try {
            Organism organism = context.getOrganism();
            List<Operand> operands = resolveOperands(context.getWorld());
            if (organism.isInstructionFailed()) {
                return;
            }
            if (getName().startsWith("DOT") || getName().startsWith("CRS")) {
                handleVectorProducts(context.getWorld(), operands);
                return;
            }

            // Handle unary operations (NEG, ABS, INC, DEC, SGN)
            String name = getName();
            if (name.startsWith("NEG") || name.startsWith("ABS") || name.startsWith("INC")
                    || name.startsWith("DEC") || name.startsWith("SGN")) {
                handleUnaryOperation(operands);
                return;
            }

            // Handle MIN/MAX operations
            if (name.startsWith("MIN") || name.startsWith("MAX")) {
                handleMinMax(operands);
                return;
            }

            if (operands.size() != 2) {
                organism.instructionFailed("Invalid operand count for arithmetic operation.");
                return;
            }

            Operand op1 = operands.get(0); // Always the destination (register or stack)
            Operand op2 = operands.get(1);

            Object result;

            // Vector Arithmetic
            if (op1.value() instanceof int[] v1 && op2.value() instanceof int[] v2) {
                if (v1.length != v2.length) {
                    organism.instructionFailed("Vector dimensions must match.");
                    return;
                }
                int[] resVec = new int[v1.length];
                String opName = getName().substring(0, 3); // "ADDR" -> "ADD"

                switch (opName) {
                    case "ADD" -> { for (int i = 0; i < v1.length; i++) resVec[i] = v1[i] + v2[i]; }
                    case "SUB" -> { for (int i = 0; i < v1.length; i++) resVec[i] = v1[i] - v2[i]; }
                    default -> {
                        organism.instructionFailed("Unsupported vector operation: " + getName());
                        return;
                    }
                }
                result = resVec;
            }
            // Scalar Arithmetic
            else if (op1.value() instanceof Integer i1 && op2.value() instanceof Integer i2) {
                Molecule s1 = org.evochora.runtime.model.Molecule.fromInt(i1);

                // Determine proper decoding for op2 based on instruction variant
                String instrName = getName();
                Molecule s2;
                if (instrName.endsWith("I")) {
                    // Immediate operand: decode stored symbol to get scalar, then rewrap with s1.type
                    Molecule imm = org.evochora.runtime.model.Molecule.fromInt(i2);
                    s2 = new Molecule(s1.type(), imm.toScalarValue());
                } else {
                    // Register or Stack operand: decode as-is
                    s2 = org.evochora.runtime.model.Molecule.fromInt(i2);
                }

                if (Config.STRICT_TYPING && s1.type() != s2.type()) {
                    organism.instructionFailed("Operand types must match in strict mode.");
                    return;
                }

                long scalarResult;
                String opName = instrName.substring(0, 3); // "ADDR" -> "ADD"

                switch (opName) {
                    case "ADD" -> scalarResult = (long) s1.toScalarValue() + s2.toScalarValue();
                    case "SUB" -> scalarResult = (long) s1.toScalarValue() - s2.toScalarValue();
                    case "MUL" -> scalarResult = (long) s1.toScalarValue() * s2.toScalarValue();
                    case "DIV" -> {
                        if (s2.toScalarValue() == 0) { organism.instructionFailed("Division by zero."); return; }
                        scalarResult = (long) s1.toScalarValue() / s2.toScalarValue();
                    }
                    case "MOD" -> {
                        if (s2.toScalarValue() == 0) { organism.instructionFailed("Modulo by zero."); return; }
                        scalarResult = (long) s1.toScalarValue() % s2.toScalarValue();
                    }
                    default -> {
                        organism.instructionFailed("Unknown scalar operation: " + instrName);
                        return;
                    }
                }
                result = new Molecule(s1.type(), (int)scalarResult).toInt();
            } else {
                organism.instructionFailed("Mismatched or invalid operand types for arithmetic operation.");
                return;
            }

            // Write result back (either to register or stack)
            if (op1.rawSourceId() != -1) { // -1 means the operand came from the stack
                if (!writeOperand(op1.rawSourceId(), result)) {
                    return;
                }
            } else {
                organism.getDataStack().push(result);
            }

        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during arithmetic operation.");
            return;
        }
    }

    private void handleVectorProducts(Environment environment, List<Operand> operands) {
        Organism organism = this.organism;
        String name = getName();
        try {
            boolean stackVariant = name.endsWith("S");
            if (stackVariant) {
                if (operands.size() != 2) { organism.instructionFailed("" + name + " expects two stack operands."); return; }
                if (!(operands.get(0).value() instanceof int[] v2) || !(operands.get(1).value() instanceof int[] v1)) {
                    organism.instructionFailed(name + " requires vector operands.");
                    return;
                }
                int result = switch (name) {
                    case "DOTS" -> dot(v1, v2);
                    case "CRSS" -> cross2d(v1, v2);
                    default -> 0;
                };
                organism.getDataStack().push(new Molecule(Config.TYPE_DATA, result).toInt());
                return;
            }

            // Register variant: %DEST, %VEC1, %VEC2
            if (operands.size() != 3) { organism.instructionFailed(name + " expects 3 operands."); return; }
            int destReg = operands.get(0).rawSourceId();
            Object v1o = operands.get(1).value();
            Object v2o = operands.get(2).value();
            if (!(v1o instanceof int[] v1) || !(v2o instanceof int[] v2)) {
                organism.instructionFailed(name + " requires vector operands.");
                return;
            }
            int result = switch (name) {
                case "DOTR" -> dot(v1, v2);
                case "CRSR" -> cross2d(v1, v2);
                default -> 0;
            };
            if (!writeOperand(destReg, new Molecule(Config.TYPE_DATA, result).toInt())) {
                return;
            }
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during vector product operation.");
            return;
        }
    }

    private int dot(int[] a, int[] b) {
        if (a.length != b.length) { this.organism.instructionFailed("Vector dimensions must match for DOT."); return 0; }
        long sum = 0;
        for (int i = 0; i < a.length; i++) sum += (long)a[i] * (long)b[i];
        return (int) sum;
    }

    private int cross2d(int[] a, int[] b) {
        if (a.length < 2 || b.length < 2) { this.organism.instructionFailed("CRS requires 2D vectors."); return 0; }
        return a[0] * b[1] - a[1] * b[0];
    }

    private void handleUnaryOperation(List<Operand> operands) {
        if (operands.size() != 1) {
            organism.instructionFailed("Unary arithmetic operation requires exactly one operand.");
            return;
        }

        Operand op1 = operands.get(0);
        if (!(op1.value() instanceof Integer i1)) {
            organism.instructionFailed("Unary arithmetic operations only support scalar values.");
            return;
        }

        Molecule s1 = Molecule.fromInt(i1);
        int value = s1.toScalarValue();
        int resultValue;

        String opName = getName().substring(0, 3); // "NEGR" -> "NEG"
        switch (opName) {
            case "NEG" -> resultValue = -value;
            case "ABS" -> resultValue = Math.abs(value);
            case "INC" -> resultValue = value + 1;
            case "DEC" -> resultValue = value - 1;
            case "SGN" -> resultValue = Integer.compare(value, 0);
            default -> {
                organism.instructionFailed("Unknown unary operation: " + getName());
                return;
            }
        }

        Object result = new Molecule(s1.type(), resultValue).toInt();

        if (op1.rawSourceId() != -1) { // Register variant
            if (!writeOperand(op1.rawSourceId(), result)) {
                return;
            }
        } else { // Stack variant
            organism.getDataStack().push(result);
        }
    }

    private void handleMinMax(List<Operand> operands) {
        if (operands.size() != 2) {
            organism.instructionFailed("MIN/MAX operations require two operands.");
            return;
        }

        Operand op1 = operands.get(0);
        Operand op2 = operands.get(1);

        if (!(op1.value() instanceof Integer i1) || !(op2.value() instanceof Integer i2)) {
            organism.instructionFailed("MIN/MAX operations only support scalar values.");
            return;
        }

        Molecule s1 = Molecule.fromInt(i1);
        String instrName = getName();
        Molecule s2;
        if (instrName.endsWith("I")) {
            // Immediate operand: decode stored symbol to get scalar, then rewrap with s1.type
            Molecule imm = Molecule.fromInt(i2);
            s2 = new Molecule(s1.type(), imm.toScalarValue());
        } else {
            s2 = Molecule.fromInt(i2);
        }

        if (Config.STRICT_TYPING && s1.type() != s2.type()) {
            organism.instructionFailed("Operand types must match in strict mode.");
            return;
        }

        int resultValue;
        String opName = instrName.substring(0, 3); // "MINR" -> "MIN"
        switch (opName) {
            case "MIN" -> resultValue = Math.min(s1.toScalarValue(), s2.toScalarValue());
            case "MAX" -> resultValue = Math.max(s1.toScalarValue(), s2.toScalarValue());
            default -> {
                organism.instructionFailed("Unknown MIN/MAX operation: " + instrName);
                return;
            }
        }

        Object result = new Molecule(s1.type(), resultValue).toInt();

        if (op1.rawSourceId() != -1) { // Register variant
            if (!writeOperand(op1.rawSourceId(), result)) {
                return;
            }
        } else { // Stack variant
            organism.getDataStack().push(result);
        }
    }

    /**
     * Plans the execution of an arithmetic instruction.
     * @param organism The organism that will execute the instruction.
     * @param environment The environment in which the instruction will be executed.
     * @return The planned instruction.
     */
    public static Instruction plan(Organism organism, Environment environment) {
        int fullOpcodeId = environment.getMolecule(organism.getIp()).value();
        return new ArithmeticInstruction(organism, fullOpcodeId);
    }
}
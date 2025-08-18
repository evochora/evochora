package org.evochora.runtime.isa.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import java.util.List;
import java.util.NoSuchElementException;

public class ArithmeticInstruction extends Instruction {

    public ArithmeticInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context) {
        try {
            Organism organism = context.getOrganism();
            List<Operand> operands = resolveOperands(context.getWorld());
            if (operands.size() != 2) {
                organism.instructionFailed("Invalid operand count for arithmetic operation.");
                return;
            }

            Operand op1 = operands.get(0); // Ist immer das Ziel (Register oder Stack)
            Operand op2 = operands.get(1);

            Object result;

            // --- Vektor-Arithmetik ---
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
            // --- Skalar-Arithmetik ---
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

            // Ergebnis zurückschreiben (entweder ins Register oder auf den Stack)
            if (op1.rawSourceId() != -1) { // -1 bedeutet, der Operand kam vom Stack
                writeOperand(op1.rawSourceId(), result);
            } else {
                organism.getDataStack().push(result);
            }

        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during arithmetic operation.");
        }
    }

    public static Instruction plan(Organism organism, Environment environment) {
        int fullOpcodeId = environment.getMolecule(organism.getIp()).toInt();
        return new ArithmeticInstruction(organism, fullOpcodeId);
    }
}